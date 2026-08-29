package com.example.ykdsummer.ai.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

/**
 * Shared utilities for MCP client implementations.
 *
 * <p>Extracts the common patterns duplicated across {@code McpVirtualTryOnService},
 * {@code McpGarmentCutoutService}, {@code McpWardrobePhotoAnalyzer},
 * {@code McpWeatherProvider} and {@code McpWebSearchTools}: tool lookup, image
 * download, result parsing, JSON extraction and logging helpers.</p>
 */
public final class McpToolSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final HttpClient IMAGE_DOWNLOAD_CLIENT = HttpClient.newBuilder().build();

    private McpToolSupport() { }

    /** Shared {@link ObjectMapper} for all MCP implementations. */
    public static ObjectMapper objectMapper() { return JSON; }

    /**
     * Linear scan for a tool by name in the MCP provider's callbacks.
     *
     * @param toolProvider the MCP tool callback provider
     * @param toolName     the tool name; blank or null returns null
     * @return the matching callback, or null if not found
     */
    public static ToolCallback findTool(SyncMcpToolCallbackProvider toolProvider, String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        for (ToolCallback callback : toolProvider.getToolCallbacks()) {
            if (toolName.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    /**
     * Parsed image result from an MCP server response.
     *
     * <p>Exactly one of {@link #bytes}/{@link #remoteUrl} (success) or {@link #error} (failure)
     * is non-null/non-blank.</p>
     */
    public record ImageResult(byte[] bytes, String remoteUrl, String error) {
        public static ImageResult image(byte[] bytes, String remoteUrl) {
            return new ImageResult(bytes, remoteUrl, null);
        }

        public static ImageResult failed(String error) {
            return new ImageResult(null, null, error);
        }

        public boolean hasImage() { return bytes != null && bytes.length > 0; }
    }

    /**
     * Parse an MCP image result JSON, preferring Base64 over URL download.
     *
     * <p>Expected JSON contract: {@code {"imageBase64":"...", "imageUrl":"...", "error":"..."}}.
     * If {@code error} is non-blank, returns a failure. If {@code imageBase64} is valid,
     * decodes it. Otherwise downloads from {@code imageUrl}.</p>
     *
     * @param raw         the raw MCP tool response
     * @param timeout     download timeout when fetching from imageUrl
     * @param errorPrefix prefix for format-error messages (e.g. "试衣服务返回格式异常")
     * @return parsed image result, never null
     */
    public static ImageResult parseImageResult(String raw, Duration timeout, String errorPrefix) {
        JsonNode node;
        try {
            node = JSON.readTree(unwrapToolResult(raw));
        } catch (JsonProcessingException exception) {
            return ImageResult.failed(errorPrefix);
        }
        if (node == null || !node.isObject()) {
            return ImageResult.failed(errorPrefix);
        }
        String serverError = node.path("error").asText("");
        if (!serverError.isBlank()) {
            return ImageResult.failed(serverError.strip());
        }
        String base64 = node.path("imageBase64").asText("");
        if (!base64.isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(base64.strip());
                if (bytes.length > 0) {
                    return ImageResult.image(bytes, node.path("imageUrl").asText(null));
                }
            } catch (IllegalArgumentException exception) {
                return ImageResult.failed(errorPrefix);
            }
        }
        String imageUrl = node.path("imageUrl").asText("");
        if (!imageUrl.isBlank()) {
            try {
                byte[] bytes = downloadImage(imageUrl.strip(), timeout);
                return ImageResult.image(bytes, imageUrl.strip());
            } catch (IOException exception) {
                return ImageResult.failed(errorPrefix);
            }
        }
        return ImageResult.failed(errorPrefix);
    }

    /**
     * Download image bytes from an HTTP(S) URL with size and timeout limits.
     *
     * @param value   the URL string
     * @param timeout request timeout
     * @return downloaded bytes
     * @throws IOException on network, scheme, or size-limit failures
     */
    public static byte[] downloadImage(String value, Duration timeout) throws IOException {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid image URL", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("unsupported image URL scheme");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "image/*")
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    IMAGE_DOWNLOAD_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("image URL returned HTTP " + response.statusCode());
            }
            try (InputStream stream = response.body()) {
                return readBytes(stream);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading image", exception);
        }
    }

    /**
     * Read an input stream into a byte array, enforcing {@value #MAX_IMAGE_BYTES} limit.
     */
    public static byte[] readBytes(InputStream stream) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > MAX_IMAGE_BYTES) {
                    throw new IOException("image exceeds maximum size");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /**
     * Unwrap a Spring AI MCP tool result: {@code SyncMcpToolCallback.call()} returns the raw
     * {@code content} list serialized as JSON, e.g.
     * {@code [{"type":"text","text":"{\"city\":\"杭州\",...}"}]}. This method extracts the
     * first textual payload so downstream parsers see the actual business JSON. Non-list or
     * unparseable values are returned unchanged.
     */
    public static String unwrapToolResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw.strip();
        }
        String value = raw.strip();
        JsonNode node;
        try {
            node = JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            return value;
        }
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    return text.strip();
                }
            }
        }
        return value;
    }

    /**
     * Extract JSON from a raw string that may be wrapped in markdown fences or contain
     * surrounding text. Returns {@code "{}"} as a safe fallback when no JSON is found.
     */
    public static String extractJson(String raw) {
        String value = unwrapToolResult(raw);
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                value = value.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        int start = objectStart < 0 ? arrayStart : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
        if (start < 0) return "{}";
        int objectEnd = value.lastIndexOf('}');
        int arrayEnd = value.lastIndexOf(']');
        int end = Math.max(objectEnd, arrayEnd);
        return end >= start ? value.substring(start, end + 1) : "{}";
    }

    /**
     * Null-safe strip that replaces NUL characters. Returns empty string for null.
     */
    public static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    /**
     * Null-safe strip with a custom default for null/blank input.
     */
    public static String safe(String value, String defaultValue) {
        String cleaned = value == null ? "" : value.replace('\u0000', ' ').strip();
        return cleaned.isEmpty() ? defaultValue : cleaned;
    }

    /**
     * Hash a userId for logging without exposing the original identifier.
     */
    public static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
