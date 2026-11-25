// ============================================================================
// Raindio Native Apple Silicon Hardware Ray-Tracing Engine (Objective-C++ / Metal 3.1)
//   - Automatically detects Apple Silicon M3 / M4 / M5+ dedicated hardware RT cores
//     via MTLDevice.supportsRaytracing && [device supportsFamily:MTLGPUFamilyApple9]
//   - ZERO-STALL ASYNC ARCHITECTURE:
//       * Batched Async BLAS + TLAS builder (single MTLCommandBuffer, atomic TLAS swap
//         in addCompletedHandler — ZERO per-section waitUntilCompleted stalls!)
//       * Dual-channel (0 = 1:1 Reflections, 1 = GI Bounces) 1-frame non-blocking
//         double-buffered async ray-tracing pipeline on Apple9+ Hardware RT silicon
//       * Outputs hitValid = 1.0 (Hardware RT Hit) or 2.0 (Hardware RT Confirmed Open Sky)
//         so Vulkan shaders completely bypass ALL software voxel DDA loops on M3/M4/M5+!
// ============================================================================

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct SectionBlas {
    id<MTLAccelerationStructure> blas = nil;
    id<MTLBuffer> primMetaBuf = nil;
    float originX = 0.0f;
    float originY = 0.0f;
    float originZ = 0.0f;
    uint32_t primCount = 0;
};

struct PendingBuild {
    int64_t key = 0;
    id<MTLAccelerationStructure> blas = nil;
    id<MTLBuffer> primMetaBuf = nil;
    id<MTLBuffer> vertBuf = nil; // kept alive until batch cmd completes
    id<MTLBuffer> scratchBuf = nil;
    MTLPrimitiveAccelerationStructureDescriptor* desc = nil;
    float originX = 0.0f;
    float originY = 0.0f;
    float originZ = 0.0f;
    uint32_t primCount = 0;
};

struct ActiveTlasSnapshot {
    id<MTLAccelerationStructure> tlas = nil;
    id<MTLBuffer> instanceDescBuf = nil;
    id<MTLBuffer> sectionMetaAddrBuf = nil;
    std::vector<id<MTLResource>> residentResources;
    uint32_t instanceCount = 0;
};

struct AsyncTraceChannel {
    id<MTLBuffer> rayInBuf[2] = { nil, nil };
    size_t rayInBytes[2] = { 0, 0 };
    id<MTLBuffer> hitOutBuf[2] = { nil, nil };
    size_t hitOutBytes[2] = { 0, 0 };
    id<MTLCommandBuffer> inFlightCmd[2] = { nil, nil };
    uint32_t inFlightCount[2] = { 0, 0 };
    int writeSlot = 0;

    bool ensureSlotBuffers(id<MTLDevice> dev, int slot, size_t rBytes, size_t hBytes) {
        if (rayInBuf[slot] == nil || rayInBytes[slot] < rBytes) {
            rayInBuf[slot] = [dev newBufferWithLength:rBytes options:MTLResourceStorageModeShared];
            rayInBytes[slot] = (rayInBuf[slot] != nil) ? rBytes : 0;
        }
        if (hitOutBuf[slot] == nil || hitOutBytes[slot] < hBytes) {
            hitOutBuf[slot] = [dev newBufferWithLength:hBytes options:MTLResourceStorageModeShared];
            hitOutBytes[slot] = (hitOutBuf[slot] != nil) ? hBytes : 0;
        }
        return (rayInBuf[slot] != nil && hitOutBuf[slot] != nil);
    }

    void reset() {
        for (int i = 0; i < 2; i++) {
            if (inFlightCmd[i] != nil) {
                [inFlightCmd[i] waitUntilCompleted];
                inFlightCmd[i] = nil;
            }
            rayInBuf[i] = nil; rayInBytes[i] = 0;
            hitOutBuf[i] = nil; hitOutBytes[i] = 0;
            inFlightCount[i] = 0;
        }
        writeSlot = 0;
    }
};

static const char* kMetalRtShaderSource = R"MSL(
#include <metal_stdlib>
#include <metal_raytracing>
using namespace metal;
using namespace metal::raytracing;

struct RayIn {
    packed_float3 origin;
    float tMax;
    packed_float3 dir;
    float active;
};

struct HitOut {
    float hitDist;      // > 0 on hit, -1 on miss
    float packedFlags;  // bits 0..2 = normalId (0..5), bit 3 = isLeaf, bits 4..11 = emissive
    float paletteId;    // 0..1023 live Minecraft block atlas palette ID
    float hitValid;     // 0.0 = inactive, 1.0 = HW RT HIT, 2.0 = HW RT CONFIRMED MISS (skip software DDA!)
};

kernel void raindio_hwrt_trace(
    uint gid [[thread_position_in_grid]],
    constant uint &rayCount [[buffer(0)]],
    const device RayIn *rays [[buffer(1)]],
    device HitOut *hits [[buffer(2)]],
    instance_acceleration_structure tlas [[buffer(3)]],
    const device uint64_t *sectionMetaAddrs [[buffer(4)]]
) {
    if (gid >= rayCount) return;

    RayIn r = rays[gid];
    if (r.active < 0.5f || r.tMax <= 0.05f) {
        hits[gid] = { -1.0f, 0.0f, 0.0f, 0.0f };
        return;
    }

    float3 d = float3(r.dir);
    float dLenSq = dot(d, d);
    if (dLenSq < 1e-8f) {
        hits[gid] = { -1.0f, 0.0f, 0.0f, 2.0f };
        return;
    }

    ray mr;
    mr.origin = float3(r.origin);
    mr.direction = d * rsqrt(dLenSq);
    mr.min_distance = 0.06f;
    mr.max_distance = r.tMax;

    intersector<triangle_data, instancing> inter;
    inter.assume_geometry_type(geometry_type::triangle);
    inter.force_opacity(forced_opacity::opaque);

    auto res = inter.intersect(mr, tlas, 0xFF);
    if (res.type == intersection_type::triangle) {
        uint instId = res.instance_id;
        uint primId = res.primitive_id;
        const device uint32_t *metaPtr = (const device uint32_t *)sectionMetaAddrs[instId];
        uint32_t meta = metaPtr[primId];
        uint32_t palId = meta & 0xFFFu;
        uint32_t normAndFlags = (meta >> 12u) & 0xFFFu;
        hits[gid] = { res.distance, float(normAndFlags), float(palId), 1.0f };
    } else {
        // 2.0 = Hardware RT confirmed unobstructed open sky (bypass software voxel DDA completely!)
        hits[gid] = { -1.0f, 0.0f, 0.0f, 2.0f };
    }
}
)MSL";

struct MetalRtState {
    std::mutex mtx;
    bool initialized = false;
    bool hwRtSupported = false;
    std::string deviceName = "Unavailable";

    id<MTLDevice> device = nil;
    id<MTLCommandQueue> traceQueue = nil;
    id<MTLCommandQueue> buildQueue = nil;
    id<MTLComputePipelineState> tracePipeline = nil;

    std::unordered_map<int64_t, SectionBlas> sections;
    std::vector<PendingBuild> pendingBuilds;
    bool tlasDirty = false;
    bool buildInFlight = false;

    // Double-buffered snapshot: trace reads activeTlas while async build constructs nextTlas
    ActiveTlasSnapshot activeTlas;

    // Channel 0 = 1:1 Reflections, Channel 1 = GI Bounces
    AsyncTraceChannel channels[2];

    void resetAll() {
        channels[0].reset();
        channels[1].reset();
        pendingBuilds.clear();
        sections.clear();
        activeTlas = ActiveTlasSnapshot{};
        tlasDirty = false;
        buildInFlight = false;
        tracePipeline = nil;
        traceQueue = nil;
        buildQueue = nil;
        device = nil;
        initialized = false;
        hwRtSupported = false;
    }
};

static MetalRtState gState;

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeInit(JNIEnv* /*env*/, jclass /*cls*/) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(gState.mtx);
        if (gState.initialized) {
            return gState.hwRtSupported ? JNI_TRUE : JNI_FALSE;
        }

        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        if (dev == nil) {
            gState.deviceName = "No Metal Device";
            gState.initialized = true;
            gState.hwRtSupported = false;
            return JNI_FALSE;
        }

        gState.deviceName = std::string([[dev name] UTF8String]);
        // MTLGPUFamilyApple9 = Apple M3 / M4 / M5+ dedicated hardware ray-tracing silicon
        BOOL hasApple9 = [dev supportsFamily:MTLGPUFamilyApple9];
        BOOL hasRaytracing = [dev supportsRaytracing];

        if (!hasApple9 || !hasRaytracing) {
            gState.initialized = true;
            gState.hwRtSupported = false;
            return JNI_FALSE;
        }

        MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
        opts.languageVersion = MTLLanguageVersion3_1;
        if (@available(macOS 15.0, *)) {
            opts.mathMode = MTLMathModeFast;
        }

        NSError* err = nil;
        NSString* src = [NSString stringWithUTF8String:kMetalRtShaderSource];
        id<MTLLibrary> lib = [dev newLibraryWithSource:src options:opts error:&err];
        if (lib == nil) {
            NSLog(@"[Raindio Metal RT] Failed to compile MSL hardware RT kernel: %@", err);
            gState.initialized = true;
            gState.hwRtSupported = false;
            return JNI_FALSE;
        }

        id<MTLFunction> fn = [lib newFunctionWithName:@"raindio_hwrt_trace"];
        if (fn == nil) {
            gState.initialized = true;
            gState.hwRtSupported = false;
            return JNI_FALSE;
        }

        id<MTLComputePipelineState> pso = [dev newComputePipelineStateWithFunction:fn error:&err];
        if (pso == nil) {
            NSLog(@"[Raindio Metal RT] Failed to create Metal compute pipeline: %@", err);
            gState.initialized = true;
            gState.hwRtSupported = false;
            return JNI_FALSE;
        }

        id<MTLCommandQueue> tq = [dev newCommandQueue];
        id<MTLCommandQueue> bq = [dev newCommandQueue];
        if (tq == nil || bq == nil) {
            gState.initialized = true;
            gState.hwRtSupported = false;
            return JNI_FALSE;
        }

        gState.device = dev;
        gState.traceQueue = tq;
        gState.buildQueue = bq;
        gState.tracePipeline = pso;
        gState.initialized = true;
        gState.hwRtSupported = true;
        return JNI_TRUE;
    }
}

JNIEXPORT jstring JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeGetDeviceName(JNIEnv* env, jclass /*cls*/) {
    std::lock_guard<std::mutex> lock(gState.mtx);
    return env->NewStringUTF(gState.deviceName.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeHasApple9HwRt(JNIEnv* /*env*/, jclass /*cls*/) {
    std::lock_guard<std::mutex> lock(gState.mtx);
    return gState.hwRtSupported ? JNI_TRUE : JNI_FALSE;
}

// Queues a chunk-section BLAS build (ZERO waitUntilCompleted — batched into 1 async command buffer!)
JNIEXPORT jboolean JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeQueueSectionMesh(
    JNIEnv* env, jclass /*cls*/,
    jlong sectionKey, jfloat originX, jfloat originY, jfloat originZ,
    jobject vertsDirectBuf, jint vertexCount,
    jobject primMetaDirectBuf, jint primCount
) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(gState.mtx);
        if (!gState.hwRtSupported || gState.device == nil) return JNI_FALSE;

        if (vertexCount <= 0 || primCount <= 0 || vertsDirectBuf == nullptr || primMetaDirectBuf == nullptr) {
            if (gState.sections.erase(sectionKey) > 0) {
                gState.tlasDirty = true;
            }
            return JNI_TRUE;
        }

        void* vertPtr = env->GetDirectBufferAddress(vertsDirectBuf);
        void* metaPtr = env->GetDirectBufferAddress(primMetaDirectBuf);
        if (vertPtr == nullptr || metaPtr == nullptr) return JNI_FALSE;

        size_t vertBytes = static_cast<size_t>(vertexCount) * 3 * sizeof(float);
        size_t metaBytes = static_cast<size_t>(primCount) * sizeof(uint32_t);

        id<MTLBuffer> vBuf = [gState.device newBufferWithBytes:vertPtr length:vertBytes options:MTLResourceStorageModeShared];
        id<MTLBuffer> mBuf = [gState.device newBufferWithBytes:metaPtr length:metaBytes options:MTLResourceStorageModeShared];
        if (vBuf == nil || mBuf == nil) return JNI_FALSE;

        MTLAccelerationStructureTriangleGeometryDescriptor* geo =
            [MTLAccelerationStructureTriangleGeometryDescriptor descriptor];
        geo.vertexBuffer = vBuf;
        geo.vertexStride = 3 * sizeof(float);
        geo.vertexFormat = MTLAttributeFormatFloat3;
        geo.triangleCount = static_cast<NSUInteger>(primCount);
        geo.opaque = YES;

        MTLPrimitiveAccelerationStructureDescriptor* blasDesc =
            [MTLPrimitiveAccelerationStructureDescriptor descriptor];
        blasDesc.geometryDescriptors = @[ geo ];

        MTLAccelerationStructureSizes sizes = [gState.device accelerationStructureSizesWithDescriptor:blasDesc];
        id<MTLAccelerationStructure> blas = [gState.device newAccelerationStructureWithSize:sizes.accelerationStructureSize];
        id<MTLBuffer> scratch = [gState.device newBufferWithLength:std::max<size_t>(sizes.buildScratchBufferSize, 256)
                                                           options:MTLResourceStorageModePrivate];
        if (blas == nil || scratch == nil) return JNI_FALSE;

        PendingBuild pb;
        pb.key = sectionKey;
        pb.blas = blas;
        pb.primMetaBuf = mBuf;
        pb.vertBuf = vBuf;
        pb.scratchBuf = scratch;
        pb.desc = blasDesc;
        pb.originX = originX;
        pb.originY = originY;
        pb.originZ = originZ;
        pb.primCount = static_cast<uint32_t>(primCount);
        gState.pendingBuilds.push_back(std::move(pb));
        gState.tlasDirty = true;
        return JNI_TRUE;
    }
}

JNIEXPORT void JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeRemoveSection(JNIEnv* /*env*/, jclass /*cls*/, jlong sectionKey) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(gState.mtx);
        if (gState.sections.erase(sectionKey) > 0) {
            gState.tlasDirty = true;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeClearSections(JNIEnv* /*env*/, jclass /*cls*/) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(gState.mtx);
        gState.pendingBuilds.clear();
        gState.sections.clear();
        gState.activeTlas = ActiveTlasSnapshot{};
        gState.tlasDirty = false;
    }
}

// Encodes ALL pending BLAS builds + TLAS rebuild into ONE asynchronous MTLCommandBuffer (ZERO main-thread stall!)
JNIEXPORT jint JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeCommitBatchAndRebuildTlasAsync(JNIEnv* /*env*/, jclass /*cls*/) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(gState.mtx);
        if (!gState.hwRtSupported || gState.device == nil || gState.buildQueue == nil) return 0;

        // If an async BLAS+TLAS build is already running on GPU, keep using activeTlas with zero stall
        if (gState.buildInFlight) {
            return static_cast<jint>(gState.activeTlas.instanceCount);
        }

        if (!gState.tlasDirty && gState.pendingBuilds.empty()) {
            return static_cast<jint>(gState.activeTlas.instanceCount);
        }

        std::vector<PendingBuild> batch = std::move(gState.pendingBuilds);
        gState.pendingBuilds.clear();

        // Register newly queued BLAS handles into section map
        for (const auto& pb : batch) {
            SectionBlas sb;
            sb.blas = pb.blas;
            sb.primMetaBuf = pb.primMetaBuf;
            sb.originX = pb.originX;
            sb.originY = pb.originY;
            sb.originZ = pb.originZ;
            sb.primCount = pb.primCount;
            gState.sections[pb.key] = std::move(sb);
        }

        if (gState.sections.empty()) {
            gState.activeTlas = ActiveTlasSnapshot{};
            gState.tlasDirty = false;
            return 0;
        }

        const uint32_t count = static_cast<uint32_t>(gState.sections.size());
        NSMutableArray<id<MTLAccelerationStructure>>* blasList = [NSMutableArray arrayWithCapacity:count];
        std::vector<MTLAccelerationStructureInstanceDescriptor> instDescs(count);
        std::vector<uint64_t> metaAddrs(count);
        std::vector<id<MTLResource>> resources;
        resources.reserve(count * 2);

        uint32_t idx = 0;
        for (const auto& kv : gState.sections) {
            const SectionBlas& sb = kv.second;
            [blasList addObject:sb.blas];
            resources.push_back(sb.blas);
            resources.push_back(sb.primMetaBuf);

            MTLAccelerationStructureInstanceDescriptor desc{};
            desc.options = MTLAccelerationStructureInstanceOptionOpaque;
            desc.mask = 0xFF;
            desc.intersectionFunctionTableOffset = 0;
            desc.accelerationStructureIndex = idx;
            desc.transformationMatrix.columns[0] = MTLPackedFloat3Make(1.0f, 0.0f, 0.0f);
            desc.transformationMatrix.columns[1] = MTLPackedFloat3Make(0.0f, 1.0f, 0.0f);
            desc.transformationMatrix.columns[2] = MTLPackedFloat3Make(0.0f, 0.0f, 1.0f);
            desc.transformationMatrix.columns[3] = MTLPackedFloat3Make(sb.originX, sb.originY, sb.originZ);
            instDescs[idx] = desc;
            metaAddrs[idx] = [sb.primMetaBuf gpuAddress];
            idx++;
        }

        id<MTLBuffer> instBuf = [gState.device newBufferWithBytes:instDescs.data()
                                                           length:instDescs.size() * sizeof(MTLAccelerationStructureInstanceDescriptor)
                                                          options:MTLResourceStorageModeShared];
        id<MTLBuffer> addrBuf = [gState.device newBufferWithBytes:metaAddrs.data()
                                                           length:metaAddrs.size() * sizeof(uint64_t)
                                                          options:MTLResourceStorageModeShared];
        if (instBuf == nil || addrBuf == nil) {
            return static_cast<jint>(gState.activeTlas.instanceCount);
        }

        MTLInstanceAccelerationStructureDescriptor* tlasDesc =
            [MTLInstanceAccelerationStructureDescriptor descriptor];
        tlasDesc.instancedAccelerationStructures = blasList;
        tlasDesc.instanceCount = count;
        tlasDesc.instanceDescriptorBuffer = instBuf;

        MTLAccelerationStructureSizes tlasSizes = [gState.device accelerationStructureSizesWithDescriptor:tlasDesc];
        id<MTLAccelerationStructure> newTlas = [gState.device newAccelerationStructureWithSize:tlasSizes.accelerationStructureSize];
        id<MTLBuffer> tlasScratch = [gState.device newBufferWithLength:std::max<size_t>(tlasSizes.buildScratchBufferSize, 256)
                                                               options:MTLResourceStorageModePrivate];
        if (newTlas == nil || tlasScratch == nil) {
            return static_cast<jint>(gState.activeTlas.instanceCount);
        }

        // Dedicated async buildQueue: BLAS+TLAS builds NEVER block ray tracing on traceQueue!
        id<MTLCommandBuffer> cmd = [gState.buildQueue commandBuffer];
        id<MTLAccelerationStructureCommandEncoder> enc = [cmd accelerationStructureCommandEncoder];
        for (const auto& pb : batch) {
            [enc buildAccelerationStructure:pb.blas descriptor:pb.desc scratchBuffer:pb.scratchBuf scratchBufferOffset:0];
        }
        [enc buildAccelerationStructure:newTlas descriptor:tlasDesc scratchBuffer:tlasScratch scratchBufferOffset:0];
        [enc endEncoding];

        // Keep temporary vertex/scratch buffers alive inside the completion block until GPU finishes
        auto keepBatch = std::make_shared<std::vector<PendingBuild>>(std::move(batch));
        auto nextSnap = std::make_shared<ActiveTlasSnapshot>();
        nextSnap->tlas = newTlas;
        nextSnap->instanceDescBuf = instBuf;
        nextSnap->sectionMetaAddrBuf = addrBuf;
        nextSnap->residentResources = std::move(resources);
        nextSnap->instanceCount = count;

        gState.buildInFlight = true;
        gState.tlasDirty = false;

        [cmd addCompletedHandler:^(id<MTLCommandBuffer> /*completed*/) {
            std::lock_guard<std::mutex> cbLock(gState.mtx);
            if (gState.initialized && gState.hwRtSupported) {
                gState.activeTlas = std::move(*nextSnap);
            }
            gState.buildInFlight = false;
            keepBatch->clear();
        }];
        [cmd commit];

        return static_cast<jint>(gState.activeTlas.instanceCount);
    }
}

// 1-Frame Non-Blocking Double-Buffered Async Hardware RT Trace!
//   - channel 0 = 1:1 Full-Resolution Reflections
//   - channel 1 = Half-Resolution GI Bounces
//   - Copies previous frame's already-completed hit buffer into hitOutDirectBuf, then asynchronously
//     kicks off the current frame's ray trace on Apple M3/M4/M5+ Hardware RT Cores with ZERO wait!
JNIEXPORT jboolean JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeTraceAsync(
    JNIEnv* env, jclass /*cls*/,
    jint channelIdx,
    jint rayCount,
    jobject rayInDirectBuf,
    jobject hitOutDirectBuf
) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(gState.mtx);
        if (!gState.hwRtSupported || gState.activeTlas.tlas == nil || gState.tracePipeline == nil
                || channelIdx < 0 || channelIdx > 1 || rayCount <= 0
                || rayInDirectBuf == nullptr || hitOutDirectBuf == nullptr) {
            return JNI_FALSE;
        }

        void* rayPtr = env->GetDirectBufferAddress(rayInDirectBuf);
        void* hitPtr = env->GetDirectBufferAddress(hitOutDirectBuf);
        if (rayPtr == nullptr || hitPtr == nullptr) return JNI_FALSE;

        AsyncTraceChannel& ch = gState.channels[channelIdx];
        const int curSlot = ch.writeSlot;
        const int prevSlot = 1 - curSlot;
        ch.writeSlot = prevSlot;

        const uint32_t count = static_cast<uint32_t>(rayCount);
        const size_t rayBytes = static_cast<size_t>(count) * 8 * sizeof(float);
        const size_t hitBytes = static_cast<size_t>(count) * 4 * sizeof(float);

        // 1. Strictly NON-BLOCKING harvest of previous frame's async trace (0.0ms main-thread stall!)
        bool hasPrevResult = false;
        if (ch.inFlightCmd[prevSlot] != nil) {
            if (ch.inFlightCmd[prevSlot].status == MTLCommandBufferStatusCompleted) {
                ch.inFlightCmd[prevSlot] = nil;
                if (ch.inFlightCount[prevSlot] == count && ch.hitOutBuf[prevSlot] != nil) {
                    std::memcpy(hitPtr, [ch.hitOutBuf[prevSlot] contents], hitBytes);
                    hasPrevResult = true;
                }
            }
        }

        // 2. If curSlot is still busy on GPU, never block — keep previous hit texture and return immediately
        if (ch.inFlightCmd[curSlot] != nil) {
            if (ch.inFlightCmd[curSlot].status != MTLCommandBufferStatusCompleted) {
                return hasPrevResult ? JNI_TRUE : JNI_FALSE;
            }
            ch.inFlightCmd[curSlot] = nil;
        }

        if (!ch.ensureSlotBuffers(gState.device, curSlot, rayBytes, hitBytes)) {
            return hasPrevResult ? JNI_TRUE : JNI_FALSE;
        }

        // 3. Fast active-ray pre-check (~5us): if no reflective water/glass pixels exist on screen,
        //    skip the entire UMA memcpy + Metal dispatch completely (0.00ms cost on dry land/caves!)
        const float* rayFloats = static_cast<const float*>(rayPtr);
        bool anyActiveRay = false;
        const uint32_t stride = (count > 2048u) ? 32u : 1u;
        for (uint32_t i = 0; i < count; i += stride) {
            if (rayFloats[i * 8u + 7u] > 0.5f) {
                anyActiveRay = true;
                break;
            }
        }
        if (!anyActiveRay) {
            return hasPrevResult ? JNI_TRUE : JNI_FALSE;
        }

        // Copy current rays and dispatch ASYNCHRONOUSLY on dedicated traceQueue (zero buildQueue contention!)
        std::memcpy([ch.rayInBuf[curSlot] contents], rayPtr, rayBytes);

        id<MTLCommandBuffer> cmd = [gState.traceQueue commandBuffer];
        id<MTLComputeCommandEncoder> enc = [cmd computeCommandEncoder];
        [enc setComputePipelineState:gState.tracePipeline];
        [enc setBytes:&count length:sizeof(uint32_t) atIndex:0];
        [enc setBuffer:ch.rayInBuf[curSlot] offset:0 atIndex:1];
        [enc setBuffer:ch.hitOutBuf[curSlot] offset:0 atIndex:2];
        [enc setAccelerationStructure:gState.activeTlas.tlas atBufferIndex:3];
        [enc setBuffer:gState.activeTlas.sectionMetaAddrBuf offset:0 atIndex:4];

        if (!gState.activeTlas.residentResources.empty()) {
            [enc useResources:gState.activeTlas.residentResources.data()
                        count:gState.activeTlas.residentResources.size()
                        usage:MTLResourceUsageRead];
        }

        NSUInteger tgWidth = std::min<NSUInteger>(256, gState.tracePipeline.maxTotalThreadsPerThreadgroup);
        [enc dispatchThreads:MTLSizeMake(count, 1, 1)
             threadsPerThreadgroup:MTLSizeMake(tgWidth, 1, 1)];
        [enc endEncoding];
        [cmd commit]; // NON-BLOCKING! Runs in parallel with Vulkan on M3/M4/M5 Hardware RT silicon!

        ch.inFlightCmd[curSlot] = cmd;
        ch.inFlightCount[curSlot] = count;

        return hasPrevResult ? JNI_TRUE : JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_raindio_vulkan_RaindioMetalRtBridge_nativeShutdown(JNIEnv* /*env*/, jclass /*cls*/) {
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(gState.mtx);
        gState.resetAll();
    }
}

} // extern "C"
