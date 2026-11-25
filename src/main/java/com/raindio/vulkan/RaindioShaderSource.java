package com.raindio.vulkan;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.raindio.RaindioClientMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * Supplies migrated GLSL 450 shader sources & includes from assets/raindio/shaders/glsl/
 * to Minecraft 26.3's RenderPearl GpuDevice.compilePipeline(...) API.
 */
public final class RaindioShaderSource implements ShaderSource {
    private static final String GLSL_ROOT = "/assets/raindio/shaders/glsl/";
    private final Map<Identifier, CachedIncludeSource> includeCache = new ConcurrentHashMap<>();

    @Override
    public String getShader(Identifier id, ShaderType type) {
        String ext = (type == ShaderType.VERTEX) ? ".vert" : ".frag";
        String path = normalizePath(id.getPath());
        if (!path.endsWith(ext)) {
            path = path + ext;
        }
        return readText(GLSL_ROOT + path);
    }

    @Override
    public CachedIncludeSource getInclude(Identifier id) {
        return includeCache.computeIfAbsent(id, key -> {
            String path = normalizePath(key.getPath());
            String source = readText(GLSL_ROOT + path);
            if (source == null) {
                return CachedIncludeSource.createError("Raindio include not found: " + GLSL_ROOT + path);
            }
            return CachedIncludeSource.create(key, source);
        });
    }

    private static String normalizePath(String path) {
        if (path.startsWith("shaders/include/")) {
            path = path.substring("shaders/include/".length());
        } else if (path.startsWith("shaders/")) {
            path = path.substring("shaders/".length());
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static String readText(String resourcePath) {
        try (InputStream in = RaindioClientMod.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                RaindioClientMod.LOGGER.error("[Raindio] Missing GLSL 450 resource: {}", resourcePath);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            RaindioClientMod.LOGGER.error("[Raindio] Failed reading GLSL 450 resource: {}", resourcePath, e);
            return null;
        }
    }

    @Override
    public void close() {
        for (CachedIncludeSource inc : includeCache.values()) {
            inc.close();
        }
        includeCache.clear();
    }
}
