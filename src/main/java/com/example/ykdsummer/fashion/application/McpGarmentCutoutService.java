package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.mcp.McpConnectionManager;
import com.example.ykdsummer.ai.mcp.McpToolSupport;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.config.FashionCutoutProperties;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MCP 适配器：调用外部抠图 MCP Server 工具替代通用图片编辑端点。
 *
 * <p>外部 Server 接收源图签名 URL 和候选元数据，返回抠图结果图片（URL 或 Base64）。
 * 抠图与修订分别映射到两个可配置的工具名；修订工具名未配置时回退到抠图工具。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.fashion.cutout", name = "provider", havingValue = "mcp")
public class McpGarmentCutoutService implements GarmentCutoutService {
    private static final Logger log = LoggerFactory.getLogger(McpGarmentCutoutService.class);
    private static final ObjectMapper JSON = McpToolSupport.objectMapper();

    private final McpConnectionManager mcp;
    private final LocalImageAssetStore imageStore;
    private final FashionCutoutProperties properties;

    public McpGarmentCutoutService(McpConnectionManager mcp,
                                   LocalImageAssetStore imageStore,
                                   FashionCutoutProperties properties) {
        this.mcp = mcp;
        this.imageStore = imageStore;
        this.properties = properties;
    }

    @Override
    public CutoutResult cutout(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction) {
        if (source == null || candidate == null) {
            return CutoutResult.failed("抠图源图片或候选信息缺失");
        }
        return callExternalTool(externalUserId, source, candidate, instruction,
                properties.getMcp().getCutoutToolName(), "cutout");
    }

    @Override
    public CutoutResult revise(String externalUserId, StoredImage draft, ClothingCandidate candidate, String instruction) {
        if (draft == null || candidate == null) {
            return CutoutResult.failed("草稿图片或候选信息缺失");
        }
        String reviseTool = properties.getMcp().getReviseToolName();
        if (reviseTool == null || reviseTool.isBlank()) {
            reviseTool = properties.getMcp().getCutoutToolName();
        }
        return callExternalTool(externalUserId, draft, candidate, instruction, reviseTool, "revise");
    }

    private CutoutResult callExternalTool(String externalUserId, StoredImage sourceImage,
                                          ClothingCandidate candidate, String instruction,
                                          String toolName, String operation) {
        try {
            String sourceImageUrl = imageStore.signedReadUrl(sourceImage);
            String raw = mcp.callTool(toolName, buildArguments(sourceImageUrl, candidate, instruction));
            if (raw == null) {
                log.warn("Garment cutout MCP tool not found, user={}, operation={}, tool={}",
                        McpToolSupport.anonymize(externalUserId), operation, toolName);
                return CutoutResult.failed("抠图 MCP 工具未配置: " + toolName);
            }
            return toResult(raw, mcpTimeout());
        } catch (Exception exception) {
            log.warn("Garment cutout MCP call failed, user={}, operation={}, tool={}, type={}",
                    McpToolSupport.anonymize(externalUserId), operation, toolName,
                    exception.getClass().getSimpleName(), exception);
            return CutoutResult.failed("抠图服务调用失败，请稍后重试");
        }
    }

    private String buildArguments(String sourceImageUrl, ClothingCandidate candidate, String instruction) {
        return JSON.createObjectNode()
                .put("sourceImageUrl", sourceImageUrl)
                .put("displayName", McpToolSupport.safe(candidate.displayName()))
                .put("category", McpToolSupport.safe(candidate.categoryCode()))
                .put("colorPrimary", McpToolSupport.safe(candidate.colorPrimary()))
                .put("instruction", McpToolSupport.safe(instruction))
                .toString();
    }

    private Duration mcpTimeout() {
        Duration configured = properties.getMcp().getTimeout();
        return configured != null ? configured : properties.getProviderTimeout();
    }

    private CutoutResult toResult(String raw, Duration timeout) {
        McpToolSupport.ImageResult result = McpToolSupport.parseImageResult(raw, timeout, "抠图服务返回格式异常");
        if (result.hasImage()) {
            return CutoutResult.image(result.bytes(), result.remoteUrl());
        }
        return CutoutResult.failed(result.error());
    }
}
