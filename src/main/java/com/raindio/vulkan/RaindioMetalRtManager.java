package com.raindio.vulkan;

import com.raindio.RaindioClientMod;
import com.raindio.client.RaindioKeybinds;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Manages per-ChunkSection Triangle BLAS & per-frame TLAS updates for Apple Silicon M3 / M4 / M5+
 * Metal Hardware Ray-Tracing Cores (via RaindioMetalRtBridge).
 *
 * - Incrementally builds face-culled triangle BLASes up to +-8 chunks (+-128m exact 1m geometry!).
 * - Tags every triangle with RaindioLpvManager's live Block Texture Atlas paletteId + face normal + leaf/emissive flags.
 * - Completely dormant (zero CPU/GPU cost) on devices without Apple9+ Hardware RT Cores.
 */
public final class RaindioMetalRtManager implements AutoCloseable {
    private static final int CHUNK_RADIUS = 8; // +-128m full 1m-exact hardware RT coverage
    private static final int EVICT_RADIUS = 9;
    private static final int MAX_BUILDS_PER_FRAME = 2;
    private static final int MAX_QUADS_PER_SECTION = 4096;

    // Reusable direct buffers for JNI zero-copy BLAS upload (max 4096 quads = 8192 tris = 24576 verts)
    private final ByteBuffer vertsScratch = ByteBuffer.allocateDirect(MAX_QUADS_PER_SECTION * 6 * 3 * 4).order(ByteOrder.nativeOrder());
    private final ByteBuffer metaScratch = ByteBuffer.allocateDirect(MAX_QUADS_PER_SECTION * 2 * 4).order(ByteOrder.nativeOrder());
    private final boolean[] solidGrid = new boolean[16 * 16 * 16];

    private final Set<Long> builtSections = new HashSet<>(512);
    private ClientLevel lastLevel = null;
    private int scanCursor = 0;
    private int localRefreshTicker = 0;
    private int activeTlasInstances = 0;
    private boolean initialized = false;
    private boolean active = false;
    private boolean announced = false;

    public boolean isReady() {
        return active && activeTlasInstances > 0;
    }

    public boolean isHardwareRtActive() {
        return active;
    }

    public String deviceName() {
        return RaindioMetalRtBridge.deviceName();
    }

    public int activeTlasInstances() {
        return activeTlasInstances;
    }

    public void updateSections(CameraRenderState cameraState, RaindioLpvManager lpv) {
        if (!initialized) {
            initialized = true;
            active = RaindioMetalRtBridge.initIfSupported();
        }
        if (!active || cameraState == null || cameraState.pos == null || lpv == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        if (!announced) {
            announced = true;
            RaindioKeybinds.sendChatAndOverlay(
                    mc,
                    "[Raindio] Metal Hardware RT Cores active (" + RaindioMetalRtBridge.deviceName() + ")"
            );
        }

        if (level != lastLevel) {
            lastLevel = level;
            builtSections.clear();
            RaindioMetalRtBridge.clearSections();
            activeTlasInstances = 0;
            scanCursor = 0;
        }

        int pcx = ((int) Math.floor(cameraState.pos.x)) >> 4;
        int pcy = ((int) Math.floor(cameraState.pos.y)) >> 4;
        int pcz = ((int) Math.floor(cameraState.pos.z)) >> 4;

        // 1. Evict distant sections beyond +-9 chunks
        Iterator<Long> it = builtSections.iterator();
        while (it.hasNext()) {
            long key = it.next();
            int cx = unpackX(key);
            int sy = unpackY(key);
            int cz = unpackZ(key);
            if (Math.abs(cx - pcx) > EVICT_RADIUS || Math.abs(cz - pcz) > EVICT_RADIUS || Math.abs(sy - pcy) > 5) {
                RaindioMetalRtBridge.removeSection(key);
                it.remove();
            }
        }

        int buildsThisFrame = 0;

        // 2. Refresh player's immediate chunk section every 30 frames for butter-smooth frame pacing
        localRefreshTicker++;
        if (localRefreshTicker >= 30) {
            localRefreshTicker = 0;
            if (tryBuildSection(level, lpv, pcx, pcy, pcz)) {
                buildsThisFrame++;
            }
        }

        // 3. Incrementally build missing chunk sections within +-8 chunks (+-128m)
        int minSY = Math.max(level.getMinY() >> 4, pcy - 3);
        int maxSY = Math.min(((level.getMinY() + level.getHeight()) >> 4) - 1, pcy + 4);
        int diameter = CHUNK_RADIUS * 2 + 1; // 17
        int totalCols = diameter * diameter; // 289

        for (int step = 0; step < 32 && buildsThisFrame < MAX_BUILDS_PER_FRAME; step++) {
            int col = (scanCursor++) % totalCols;
            int dx = (col % diameter) - CHUNK_RADIUS;
            int dz = (col / diameter) - CHUNK_RADIUS;
            int cx = pcx + dx;
            int cz = pcz + dz;

            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            LevelChunkSection[] sections = chunk.getSections();

            for (int sy = minSY; sy <= maxSY && buildsThisFrame < MAX_BUILDS_PER_FRAME; sy++) {
                long key = packKey(cx, sy, cz);
                if (builtSections.contains(key)) continue;

                int sIdx = level.getSectionIndexFromSectionY(sy);
                if (sIdx < 0 || sIdx >= sections.length) continue;
                LevelChunkSection sec = sections[sIdx];
                if (sec == null || sec.hasOnlyAir()) {
                    builtSections.add(key);
                    continue;
                }

                if (buildSectionMeshAndUpload(sec, lpv, key, cx, sy, cz)) {
                    builtSections.add(key);
                    buildsThisFrame++;
                }
            }
        }

        activeTlasInstances = RaindioMetalRtBridge.commitBatchAndRebuildTlasAsync();
    }

    public boolean traceReflectionRaysAsync(int rayCount, ByteBuffer rayInDirectBuf, ByteBuffer hitOutDirectBuf) {
        if (!isReady()) return false;
        return RaindioMetalRtBridge.traceAsync(RaindioMetalRtBridge.CHANNEL_REFLECTIONS, rayCount, rayInDirectBuf, hitOutDirectBuf);
    }

    public boolean traceGiRaysAsync(int rayCount, ByteBuffer rayInDirectBuf, ByteBuffer hitOutDirectBuf) {
        if (!isReady()) return false;
        return RaindioMetalRtBridge.traceAsync(RaindioMetalRtBridge.CHANNEL_GI_BOUNCE, rayCount, rayInDirectBuf, hitOutDirectBuf);
    }

    private boolean tryBuildSection(ClientLevel level, RaindioLpvManager lpv, int cx, int sy, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return false;
        int sIdx = level.getSectionIndexFromSectionY(sy);
        LevelChunkSection[] sections = chunk.getSections();
        if (sIdx < 0 || sIdx >= sections.length) return false;
        LevelChunkSection sec = sections[sIdx];
        long key = packKey(cx, sy, cz);
        if (sec == null || sec.hasOnlyAir()) {
            if (builtSections.remove(key)) {
                RaindioMetalRtBridge.removeSection(key);
            }
            return false;
        }
        boolean ok = buildSectionMeshAndUpload(sec, lpv, key, cx, sy, cz);
        if (ok) builtSections.add(key);
        return ok;
    }

    private boolean buildSectionMeshAndUpload(
            LevelChunkSection sec, RaindioLpvManager lpv,
            long key, int cx, int sy, int cz
    ) {
        // Pass 1: mark solid/leaf occupancy for fast intra-section face culling
        for (int y = 0; y < 16; y++) {
            int yOff = y << 8;
            for (int z = 0; z < 16; z++) {
                int yzOff = yOff | (z << 4);
                for (int x = 0; x < 16; x++) {
                    BlockState st = sec.getBlockState(x, y, z);
                    boolean occ = !st.isAir() && st.getFluidState().isEmpty()
                            && ((st.canOcclude() && st.isSolidRender()) || (st.getBlock() instanceof LeavesBlock));
                    solidGrid[yzOff | x] = occ;
                }
            }
        }

        vertsScratch.clear();
        metaScratch.clear();
        int quadCount = 0;

        // Pass 2: emit exposed faces (2 triangles per quad) with exact paletteId + normal + flags
        for (int y = 0; y < 16 && quadCount < MAX_QUADS_PER_SECTION; y++) {
            int yOff = y << 8;
            for (int z = 0; z < 16 && quadCount < MAX_QUADS_PER_SECTION; z++) {
                int yzOff = yOff | (z << 4);
                for (int x = 0; x < 16 && quadCount < MAX_QUADS_PER_SECTION; x++) {
                    if (!solidGrid[yzOff | x]) continue;

                    boolean fPosX = (x == 15) || !solidGrid[yzOff | (x + 1)];
                    boolean fNegX = (x == 0)  || !solidGrid[yzOff | (x - 1)];
                    boolean fPosY = (y == 15) || !solidGrid[((y + 1) << 8) | (z << 4) | x];
                    boolean fNegY = (y == 0)  || !solidGrid[((y - 1) << 8) | (z << 4) | x];
                    boolean fPosZ = (z == 15) || !solidGrid[yOff | ((z + 1) << 4) | x];
                    boolean fNegZ = (z == 0)  || !solidGrid[yOff | ((z - 1) << 4) | x];
                    if (!fPosX && !fNegX && !fPosY && !fNegY && !fPosZ && !fNegZ) continue;

                    BlockState st = sec.getBlockState(x, y, z);
                    Block b = st.getBlock();
                    int palId = lpv.resolveBlockPaletteId(st) & 0xFFF;
                    int isLeafBit = (b instanceof LeavesBlock) ? (1 << 15) : 0;
                    int emByte = Math.min(255, st.getLightEmission() * 17) << 16;
                    int baseMeta = palId | isLeafBit | emByte;

                    float fx = x, fy = y, fz = z;
                    if (fPosX && quadCount < MAX_QUADS_PER_SECTION) {
                        emitQuad(fx + 1, fy, fz, fx + 1, fy + 1, fz, fx + 1, fy + 1, fz + 1, fx + 1, fy, fz + 1, baseMeta | (0 << 12));
                        quadCount++;
                    }
                    if (fNegX && quadCount < MAX_QUADS_PER_SECTION) {
                        emitQuad(fx, fy, fz + 1, fx, fy + 1, fz + 1, fx, fy + 1, fz, fx, fy, fz, baseMeta | (1 << 12));
                        quadCount++;
                    }
                    if (fPosY && quadCount < MAX_QUADS_PER_SECTION) {
                        emitQuad(fx, fy + 1, fz, fx, fy + 1, fz + 1, fx + 1, fy + 1, fz + 1, fx + 1, fy + 1, fz, baseMeta | (2 << 12));
                        quadCount++;
                    }
                    if (fNegY && quadCount < MAX_QUADS_PER_SECTION) {
                        emitQuad(fx, fy, fz + 1, fx, fy, fz, fx + 1, fy, fz, fx + 1, fy, fz + 1, baseMeta | (3 << 12));
                        quadCount++;
                    }
                    if (fPosZ && quadCount < MAX_QUADS_PER_SECTION) {
                        emitQuad(fx + 1, fy, fz + 1, fx + 1, fy + 1, fz + 1, fx, fy + 1, fz + 1, fx, fy, fz + 1, baseMeta | (4 << 12));
                        quadCount++;
                    }
                    if (fNegZ && quadCount < MAX_QUADS_PER_SECTION) {
                        emitQuad(fx, fy, fz, fx, fy + 1, fz, fx + 1, fy + 1, fz, fx + 1, fy, fz, baseMeta | (5 << 12));
                        quadCount++;
                    }
                }
            }
        }

        if (quadCount == 0) {
            RaindioMetalRtBridge.removeSection(key);
            return true;
        }

        vertsScratch.flip();
        metaScratch.flip();
        return RaindioMetalRtBridge.queueSectionMesh(
                key,
                cx * 16.0f, sy * 16.0f, cz * 16.0f,
                vertsScratch, quadCount * 6,
                metaScratch, quadCount * 2
        );
    }

    private void emitQuad(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            int packedMeta
    ) {
        // Triangle 1: (0, 1, 2)
        vertsScratch.putFloat(x0).putFloat(y0).putFloat(z0);
        vertsScratch.putFloat(x1).putFloat(y1).putFloat(z1);
        vertsScratch.putFloat(x2).putFloat(y2).putFloat(z2);
        metaScratch.putInt(packedMeta);
        // Triangle 2: (0, 2, 3)
        vertsScratch.putFloat(x0).putFloat(y0).putFloat(z0);
        vertsScratch.putFloat(x2).putFloat(y2).putFloat(z2);
        vertsScratch.putFloat(x3).putFloat(y3).putFloat(z3);
        metaScratch.putInt(packedMeta);
    }

    private static long packKey(int cx, int sy, int cz) {
        return (((long) cx & 0x3FFFFFL) << 42) | (((long) cz & 0x3FFFFFL) << 20) | ((long) sy & 0xFFFFFL);
    }

    private static int unpackX(long k) { return (int) (k >> 42); }
    private static int unpackZ(long k) { return (int) ((k << 22) >> 42); }
    private static int unpackY(long k) { return (int) ((k << 44) >> 44); }

    @Override
    public void close() {
        builtSections.clear();
        lastLevel = null;
        activeTlasInstances = 0;
        if (active) {
            RaindioMetalRtBridge.shutdown();
            active = false;
        }
        initialized = false;
        announced = false;
        RaindioClientMod.LOGGER.info("[Raindio Metal RT] Closed Apple Silicon Metal Hardware RT manager.");
    }
}
