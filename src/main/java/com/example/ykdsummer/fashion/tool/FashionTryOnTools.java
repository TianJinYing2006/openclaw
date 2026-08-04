package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.fashion.ReferenceImageResolver;
import com.example.ykdsummer.ai.mcp.McpToolSupport;
import com.example.ykdsummer.ai.orchestration.AgentTool;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionVirtualTryOnService;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnTaskStatus;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Agent boundary for a user-approved wardrobe item plus active person-template virtual try-on. */
@AgentTool
@Component
@ConditionalOnBean(FashionVirtualTryOnService.class)
public class FashionTryOnTools implements AiTool {
    private static final Duration IMAGE_DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);

    /** 参考单品图下载器；默认走 {@link McpToolSupport#downloadImage}，测试可注入替身。 */
    public interface ReferenceImageDownloader {
        byte[] download(String url, Duration timeout) throws java.io.IOException;
    }

    private static byte[] defaultDownload(String url, Duration timeout) throws java.io.IOException {
        return McpToolSupport.downloadImage(url, timeout);
    }

    private final FashionVirtualTryOnService tryOn;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;
    private final ReferenceImageResolver imageResolver;
    private ReferenceImageDownloader downloader = FashionTryOnTools::defaultDownload;

    public FashionTryOnTools(FashionVirtualTryOnService tryOn, ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this(tryOn, artifacts, trace, null);
    }

    @Autowired
    public FashionTryOnTools(FashionVirtualTryOnService tryOn, ToolArtifactCollector artifacts, AiTraceLogger trace,
                             ReferenceImageResolver imageResolver) {
        this.tryOn = tryOn;
        this.artifacts = artifacts;
        this.trace = trace;
        this.imageResolver = imageResolver;
    }

    @Autowired(required = false)
    public void setDownloader(ReferenceImageDownloader downloader) {
        if (downloader != null) {
            this.downloader = downloader;
        }
    }

    @Tool(name = "virtual_try_on_wardrobe_item", description = "把用户衣橱里的某件具体单品穿到当前试衣模板上，仅当用户明确针对衣橱单品时调用。"
            + "典型场景：用户刚把某件衣服加入衣橱后说\"试穿一下/穿一下/试试这件\"，或明确要试穿衣橱里的具体单品。"
            + "决策规则：当\"试穿\"类表达没有指明推荐方案，且最近一步是衣橱操作（加入衣橱/预览衣橱单品）时，必须调用本工具，"
            + "即使对话中曾出现过穿搭推荐方案（那是更早的上下文，不应覆盖最近的衣橱意图），绝不能自行挑选历史 outfit 编号。"
            + "调用前应先用 search_wardrobe，或 select_wardrobe_preview_item，确认衣橱单品的内部编号；"
            + "绝不能把推荐方案编号或对话中其他 outfit 编号当作 wardrobeItemId 传入。"
            + "不能因为用户只是询问搭配或展示衣橱就自动调用。"
            + "系统使用当前启用的人物模板和该单品的主图片，在后台生成，完成后自动发回微信。")
    public String virtualTryOnWardrobeItem(
            @ToolParam(description = "来自 search_wardrobe 或 select_wardrobe_preview_item 的内部 wardrobeItemId。") long wardrobeItemId
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("virtual_try_on_wardrobe_item", "wardrobeItem=" + wardrobeItemId);
        try {
            FashionTryOnTask task = tryOn.submit(userId, wardrobeItemId);
            String message = task.status() == FashionTryOnTaskStatus.PROCESSING
                    ? "这件衣服正在生成上身效果，完成后会自动发给你。"
                    : "已开始生成这件衣服的上身效果。后台完成后会自动把图片发给你。";
            trace.toolResult("virtual_try_on_wardrobe_item", message);
            return message;
        } catch (IllegalArgumentException failure) {
            return failed("virtual_try_on_wardrobe_item", failure,
                    "暂时不能试穿这件衣服：请先确认它已加入当前用户的衣橱并且有单品展示图。" );
        } catch (IllegalStateException failure) {
            return failed("virtual_try_on_wardrobe_item", failure,
                    "还不能开始试衣：请先上传并启用一张清晰的全身人物模板。" );
        } catch (RuntimeException failure) {
            return failed("virtual_try_on_wardrobe_item", failure, "提交试衣任务失败，请稍后重试。" );
        }
    }

    @Tool(name = "check_virtual_tryon_status", description = "当用户询问上身效果是否完成、试衣是否仍在生成、或刚才的试衣失败原因时调用。"
            + "只查询当前微信用户最近一次虚拟试衣任务，绝不能猜测状态。")
    public String checkVirtualTryOnStatus() {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("check_virtual_tryon_status", "latest");
        try {
            Optional<FashionTryOnTask> task = tryOn.latest(userId);
            String message = task.map(this::describe).orElse("当前没有已提交的上身试衣任务。");
            trace.toolResult("check_virtual_tryon_status", message);
            return message;
        } catch (RuntimeException failure) {
            return failed("check_virtual_tryon_status", failure, "读取试衣任务状态失败，请稍后再试。" );
        }
    }

    @Tool(name = "virtual_try_on_reference_outfit", description = "把用户刚获得的穿搭推荐方案中的单品穿到当前试衣模板上。"
            + "仅当用户明确引用刚才的推荐方案时调用，如\"试试这套/穿刚才那套/这套衣服上身效果\"，且上下文[内部最近穿搭推荐方案]中存在该编号。"
            + "前提：用户已通过 fashion_consultant 获得推荐方案，且用户已上传并启用人物模板。"
            + "排除规则：若用户最近一步操作是加入/预览衣橱单品后说\"试穿一下/穿一下\"（未指明推荐方案），必须调用 virtual_try_on_wardrobe_item，不得调用本工具；"
            + "不得从历史对话中自行挑选 outfit 编号，只能使用上下文[内部最近穿搭推荐方案]中刚产生的那一个。"
            + "入参 referenceOutfitId 使用上下文[内部最近穿搭推荐方案]中的 outfit 编号（如 002/153），即用户刚看到的那套推荐；"
            + "系统自动从参考库下载该方案的单品图并配合当前启用的人物模板在后台生成，用户无需重新发图。"
            + "注意：不要为试穿调用 fashion_consultant（那会生成一套新方案并发来新图片）；仅当从未有过任何推荐方案时才调用它获取。")
    public String virtualTryOnReferenceOutfit(
            @ToolParam(description = "穿搭推荐方案中的 outfit 编号，例如 002、010、153。") String referenceOutfitId,
            @ToolParam(required = false, description = "要试穿的单品类型：top（上衣）、bottom（下装）、overall（连衣裙/连体裤）。"
                    + "默认 top。用户说\"试穿上衣\"传 top，\"试穿裤子\"传 bottom，\"试穿连衣裙\"传 overall。") String garmentType
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("virtual_try_on_reference_outfit", "outfit=" + referenceOutfitId + ", type=" + garmentType);
        if (imageResolver == null) {
            return failed("virtual_try_on_reference_outfit", new IllegalStateException("参考图解析器未配置"),
                    "参考穿搭图库暂不可用，请稍后再试。" );
        }
        try {
            ReferenceImageResolver.GarmentImage garment = pickGarment(
                    imageResolver.garmentsFor(referenceOutfitId), safe(garmentType));
            if (garment == null) {
                return "这套推荐方案暂没有可用于试穿的单品图。";
            }
            byte[] bytes;
            try {
                bytes = downloader.download(garment.url(), IMAGE_DOWNLOAD_TIMEOUT);
            } catch (java.io.IOException failure) {
                return failed("virtual_try_on_reference_outfit", new IllegalStateException("参考单品图下载失败", failure),
                        "参考单品图下载失败，请稍后重试。" );
            }
            FashionTryOnTask task = tryOn.submitWithReferenceOutfit(userId, referenceOutfitId, bytes,
                    categoryCode(garment.garment()));
            String message = task.status() == FashionTryOnTaskStatus.PROCESSING
                    ? "这件推荐单品正在生成上身效果，完成后会自动发给你。"
                    : "已开始生成这件推荐单品的上身效果。后台完成后会自动把图片发给你。";
            trace.toolResult("virtual_try_on_reference_outfit", message);
            return message;
        } catch (IllegalArgumentException failure) {
            return failed("virtual_try_on_reference_outfit", failure,
                    "暂时不能试穿这套推荐方案：参考单品图下载或保存失败，请稍后重试。" );
        } catch (IllegalStateException failure) {
            return failed("virtual_try_on_reference_outfit", failure,
                    "还不能开始试衣：请先上传并启用一张清晰的全身人物模板。" );
        } catch (RuntimeException failure) {
            return failed("virtual_try_on_reference_outfit", failure, "提交试衣任务失败，请稍后重试。" );
        }
    }

    private static ReferenceImageResolver.GarmentImage pickGarment(
            List<ReferenceImageResolver.GarmentImage> garments, String garmentType) {
        if (garments == null || garments.isEmpty()) {
            return null;
        }
        // 无指定类型时默认选上衣
        String target = safe(garmentType).isBlank() ? "top" : garmentType.toLowerCase(Locale.ROOT);
        // 精确匹配目标类型
        return garments.stream()
                .filter(garment -> target.equals(safe(garment.garment()).toLowerCase(Locale.ROOT)))
                .findFirst()
                // 匹配不到时 fallback 到第一件单品
                .orElse(garments.getFirst());
    }

    private static String categoryCode(String garment) {
        return switch (safe(garment).toLowerCase(Locale.ROOT)) {
            case "bottom", "pants", "jeans", "skirt" -> "STRAIGHT_PANTS";
            case "overall", "dress", "jumpsuit" -> "DRESS";
            case "shoes", "bag", "accessory" -> "ACCESSORY";
            default -> "T_SHIRT";
        };
    }

    private String describe(FashionTryOnTask task) {
        return switch (task.status()) {
            case SUBMITTED -> "上身效果已进入后台队列，马上会开始生成；完成后会自动发图。";
            case PROCESSING -> "上身效果正在后台生成，完成后会自动发图。";
            case SUCCEEDED -> "最近一次上身效果已生成完成并保存；若没有收到图片，可让我重新发送最近图片。";
            case FAILED -> "最近一次上身效果没有生成成功：" + safe(task.failureSummary()) + "。可以重新试一次。";
        };
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }
    private String failed(String name, RuntimeException failure, String message) {
        trace.toolFailure(name, failure);
        return message;
    }
    private static String unavailable() { return "当前会话身份不可用，暂时不能使用个人试衣功能。"; }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
