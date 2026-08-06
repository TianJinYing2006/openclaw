package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.fashion.FashionAgentService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(FashionTryOnTools.class);
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
            + "用户明确提到衣橱具体单品（如\"灰色T恤/红色T恤/那件XX/衣柜里的XX\"，或最近刚查看/筛选过衣橱后说\"试一下XX\"）时，"
            + "必须调用本工具，严禁改用 virtual_try_on_reference_outfit（那个工具装的是参考推荐库的衣服，与衣橱单品完全不同）。"
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
            + "在用户刚通过 fashion_consultant 获得推荐方案后，说\"试穿/穿一下/试试/上身效果\"等表达、且未明确指向衣橱单品（未提\"衣柜/衣橱里的\"）时，"
            + "默认指刚推荐的方案，必须调用本工具，不得只口头承诺试穿。上下文[内部最近穿搭推荐方案]中存在该编号。"
            + "用户未指明具体单品（如\"试穿这套/试穿一下吧\"）时不要传 garmentType：默认整套试穿，把方案中的上衣与下装拼成一张穿搭图，"
            + "后台一次生成一张全套上身效果图并推送；"
            + "用户明确说\"试穿上衣/裤子/连衣裙\"时才传对应 garmentType，只试穿那一件。"
            + "前提：用户已通过 fashion_consultant 获得推荐方案，且用户已上传并启用人物模板。"
            + "排除规则：若用户最近一步操作是加入/预览衣橱单品后说\"试穿一下/穿一下/试试\"（未指明推荐方案），必须调用 virtual_try_on_wardrobe_item，不得调用本工具；"
            + "用户提到衣橱具体单品（如\"灰色T恤/红色T恤/那件XX/衣柜里的XX\"，或最近刚查看/筛选过衣橱后说\"试一下XX\"）时，禁止调用本工具，"
            + "必须改用 virtual_try_on_wardrobe_item 试穿该衣橱单品（本工具只装参考推荐库的衣服）；"
            + "不得从历史对话中自行挑选 outfit 编号，只能使用上下文[内部最近穿搭推荐方案]中刚产生的那一个。"
            + "入参 referenceOutfitId 使用上下文[内部最近穿搭推荐方案]中的 outfit 编号（如 002/153），即用户刚看到的那套推荐；"
            + "系统自动从参考库下载该方案的单品图并配合当前启用的人物模板在后台生成，用户无需重新发图。"
            + "注意：不要为试穿调用 fashion_consultant（那会生成一套新方案并发来新图片）；仅当从未有过任何推荐方案时才调用它获取。")
    public String virtualTryOnReferenceOutfit(
            @ToolParam(description = "穿搭推荐方案中的 outfit 编号，例如 002、010、153。") String referenceOutfitId,
            @ToolParam(required = false, description = "要试穿的单品类型：top（上衣）、bottom（下装）、overall（连衣裙/连体裤）。"
                    + "留空=整套试穿（方案中全部单品逐件生成效果图并逐张推送）；用户明确说\"试穿上衣/裤子/连衣裙\"时传 top/bottom/overall 只试那一件。") String garmentType
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("virtual_try_on_reference_outfit", "outfit=" + referenceOutfitId + ", type=" + garmentType);
        if (imageResolver == null) {
            return failed("virtual_try_on_reference_outfit", new IllegalStateException("参考图解析器未配置"),
                    "参考穿搭图库暂不可用，请稍后再试。" );
        }
        try {
            List<ReferenceImageResolver.GarmentImage> garments = imageResolver.garmentsFor(referenceOutfitId);
            if (!safe(garmentType).isBlank()) {
                // 用户明确指定单品：只试穿那一件
                ReferenceImageResolver.GarmentImage garment = pickGarment(garments, garmentType);
                if (garment == null) {
                    return "这套推荐方案暂没有可用于试穿的单品图。";
                }
                return submitOne(userId, referenceOutfitId, garment);
            }
            // 用户未指明单品：整套试穿，上衣 + 下装拼成一张穿搭图一次出图
            return submitFullOutfit(userId, referenceOutfitId, garments);
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

    /** 单件试穿：下载指定单品图并提交一个后台试衣任务。 */
    private String submitOne(String userId, String referenceOutfitId, ReferenceImageResolver.GarmentImage garment) {
        byte[] bytes;
        try {
            bytes = downloader.download(garment.url(), IMAGE_DOWNLOAD_TIMEOUT);
        } catch (java.io.IOException failure) {
            return failed("virtual_try_on_reference_outfit", new IllegalStateException("参考单品图下载失败", failure),
                    "参考单品图下载失败，请稍后重试。" );
        }
        FashionTryOnTask task = tryOn.submitWithReferenceOutfit(userId, referenceOutfitId, bytes,
                categoryCode(garment.garment()));
        return task.status() == FashionTryOnTaskStatus.PROCESSING
                ? "这件推荐单品正在生成上身效果，完成后会自动发给你。"
                : "已开始生成这件推荐单品的上身效果。后台完成后会自动把图片发给你。";
    }

    /** 整套试穿：把上衣 + 下装上下拼成一张穿搭图，作为单件一次提交，后台一次出图回一张全套效果图。 */
    private String submitFullOutfit(String userId, String referenceOutfitId,
                                    List<ReferenceImageResolver.GarmentImage> garments) {
        if (garments == null || garments.isEmpty()) {
            return "这套推荐方案暂没有可用于试穿的单品图。";
        }
        // 优先取上衣 + 下装各一件，上下拼接成一张穿搭图（上衣在上、下装在下）
        ReferenceImageResolver.GarmentImage top = null;
        ReferenceImageResolver.GarmentImage bottom = null;
        for (ReferenceImageResolver.GarmentImage garment : garments) {
            String role = safe(garment.garment()).toLowerCase(Locale.ROOT);
            if (top == null && isTop(role)) top = garment;
            else if (bottom == null && isBottom(role)) bottom = garment;
        }
        if (top != null && bottom != null) {
            byte[] collage = buildCollage(top.url(), bottom.url());
            if (collage != null) {
                FashionTryOnTask task = tryOn.submitWithReferenceOutfit(userId, referenceOutfitId, collage,
                        "FULL_OUTFIT");
                return task.status() == FashionTryOnTaskStatus.PROCESSING
                        ? "已开始整套试穿：上衣和下装会一次性穿上，完成后会把全套效果图发给你。"
                        : "已开始整套试穿：上衣和下装会一次性穿上，后台完成后会自动把全套效果图发给你。";
            }
            log.warn("Full-outfit collage failed, falling back to single garment: outfit={}", referenceOutfitId);
        }
        // 缺少上/下装（如连衣裙方案）或拼图失败：退回单件试穿，保证用户仍能拿到上身效果
        ReferenceImageResolver.GarmentImage only = top != null ? top : (bottom != null ? bottom : garments.getFirst());
        return submitOne(userId, referenceOutfitId, only);
    }

    /** 下载上衣与下装图并上下拼接成一张穿搭拼图；下载或解码失败返回 null。 */
    private byte[] buildCollage(String topUrl, String bottomUrl) {
        byte[] top;
        byte[] bottom;
        try {
            top = downloader.download(topUrl, IMAGE_DOWNLOAD_TIMEOUT);
            bottom = downloader.download(bottomUrl, IMAGE_DOWNLOAD_TIMEOUT);
        } catch (java.io.IOException failure) {
            log.warn("Full-outfit garment download failed: {}", failure.getMessage());
            return null;
        }
        return FashionAgentService.buildGarmentCollage(top, bottom);
    }

    private static boolean isTop(String role) {
        return switch (role) {
            case "top", "shirt", "blouse", "sweater", "hoodie", "outerwear", "jacket", "coat", "dress", "overall" -> true;
            default -> false;
        };
    }

    private static boolean isBottom(String role) {
        return switch (role) {
            case "bottom", "pants", "jeans", "trousers", "shorts", "skirt", "leggings" -> true;
            default -> false;
        };
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
