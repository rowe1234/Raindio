package com.raindio.vulkan;

import com.raindio.RaindioClientMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads pre-compiled SPIR-V (.spv) modules packaged under assets/raindio/shaders/spirv/.
 */
public final class RaindioSpirvLoader {
    private static final String SPIRV_ROOT = "/assets/raindio/shaders/spirv/";
    private static final int SPIRV_MAGIC = 0x07230203;

    private final Map<String, ByteBuffer> cache = new ConcurrentHashMap<>();

    public ByteBuffer load(String relativeSpvPath) {
        return cache.computeIfAbsent(relativeSpvPath, this::readAndValidate);
    }

    private ByteBuffer readAndValidate(String relativeSpvPath) {
        String resourcePath = SPIRV_ROOT + relativeSpvPath;
        try (InputStream in = RaindioClientMod.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing compiled SPIR-V resource: " + resourcePath);
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length < 20 || (bytes.length & 3) != 0) {
                throw new IllegalStateException("Invalid SPIR-V bytecode length (" + bytes.length + "): " + resourcePath);
            }
            ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            direct.put(bytes).flip();

            int magic = direct.getInt(0);
            if (magic != SPIRV_MAGIC) {
                throw new IllegalStateException(String.format(
                        "Invalid SPIR-V magic 0x%08X in %s (expected 0x%08X)", magic, resourcePath, SPIRV_MAGIC));
            }
            return direct.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load SPIR-V resource: " + resourcePath, e);
        }
    }

    public int verifyCoreModules() {
        String[] core = {
                "chunks/chunk_shadow.vert.spv",
                "chunks/chunk_shadow.frag.spv",
                "chunks/chunk_terrain.vert.spv",
                "chunks/chunk_terrain.frag.spv",
                "chunks/chunk_water.vert.spv",
                "chunks/chunk_water.frag.spv",
                "composite/composite.frag.spv",
                "composite/composite1.frag.spv",
                "composite/composite2.frag.spv",
                "composite/composite3.frag.spv",
                "composite/composite4.frag.spv",
                "composite/composite5.frag.spv",
                "composite/final.frag.spv",
                "compute/lpv_propagate.comp.spv",
                "compute/restir_di_gi_temporal.comp.spv",
                "compute/restir_di_gi_spatial_resolve.comp.spv"
        };
        for (String path : core) {
            load(path);
        }
        return core.length;
    }

    public void clear() {
        cache.clear();
    }
}
