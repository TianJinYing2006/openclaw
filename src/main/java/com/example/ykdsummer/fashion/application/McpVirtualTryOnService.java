package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.mcp.McpConnectionManager;
import com.example.ykdsummer.ai.mcp.McpToolSupport;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.config.FashionTryOnProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MCP adapter: calls the dedicated virtual try-on MCP Server tool instead of the generic image-edit endpoint.
 *
 * <p>The external server receives signed URLs for the person template and the garment, renders a try-on
 * preview, and returns either a result image URL or a Base64 payload.</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.fashion.tryon", name = "provider", havingValue = "mcp")
public class McpVirtualTryOnService implements VirtualTryOnService {
    private static final Logger log = LoggerFactory.getLogger(McpVirtualTryOnService.class);
    private static final ObjectMapper JSON = McpToolSupport.objectMapper();

    private final McpConnectionManager mcp;
    private final LocalImageAssetStore imageStore;
    private final FashionTryOnProperties properties;

    public McpVirtualTryOnService(McpConnectionManager mcp, LocalImageAssetStore imageStore,
                                  FashionTryOnProperties properties) {
        this.mcp = mcp;
        this.imageStore = imageStore;
        this.properties = properties;
    }

    @Override
    public TryOnResult render(String externalUserId, StoredImage personImage, StoredImage garmentImage,
                              String garmentCategoryCode, Duration timeout) {
        if (personImage == null || garmentImage == null) {
            return TryOnResult.failed("试衣素材图片不可用");
        }
        String toolName = properties.getMcp().getToolName();
        try {
            String personImageUrl = imageStore.signedReadUrl(personImage);
            String garmentImageUrl = imageStore.signedReadUrl(garmentImage);
            String raw = mcp.callTool(toolName, buildArguments(personImageUrl, garmentImageUrl, garmentCategoryCode));
            if (raw == null) {
                log.warn("Virtual try-on MCP tool not found, user={}, tool={}", McpToolSupport.anonymize(externalUserId), toolName);
                return TryOnResult.failed("试衣 MCP 工具未配置: " + toolName);
            }
            return toResult(raw, mcpTimeout(timeout));
        } catch (Exception exception) {
            log.warn("Virtual try-on MCP call failed, user={}, tool={}, type={}", McpToolSupport.anonymize(externalUserId), toolName,
                    exception.getClass().getSimpleName(), exception);
            return TryOnResult.failed("试衣服务调用失败，请稍后重试");
        }
    }

    private String buildArguments(String personImageUrl, String garmentImageUrl, String garmentCategoryCode) {
        return JSON.createObjectNode()
                .put("personImageUrl", personImageUrl)
                .put("garmentImageUrl", garmentImageUrl)
                .put("garmentCategory", McpToolSupport.safe(garmentCategoryCode, "UNKNOWN"))
                .toString();
    }

    private Duration mcpTimeout(Duration fallback) {
        Duration configured = properties.getMcp().getTimeout();
        return configured != null ? configured : fallback;
    }

    private TryOnResult toResult(String raw, Duration timeout) {
        McpToolSupport.ImageResult result = McpToolSupport.parseImageResult(raw, timeout, "试衣服务返回格式异常");
        if (result.hasImage()) {
            return TryOnResult.image(result.bytes(), result.remoteUrl());
        }
        return TryOnResult.failed(result.error());
    }
}
