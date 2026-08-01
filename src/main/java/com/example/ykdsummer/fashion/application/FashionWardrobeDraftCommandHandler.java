package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.GarmentDraftVersion;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Handles unambiguous natural-language changes to the single garment draft currently awaiting confirmation.
 *
 * <p>The Agent still owns the general tool-calling workflow. This narrow guard prevents a conversational model
 * from claiming that a clearly requested garment revision was submitted when no durable task was created.</p>
 */
@Component
@ConditionalOnBean(FashionWardrobeIngestionService.class)
public class FashionWardrobeDraftCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(FashionWardrobeDraftCommandHandler.class);
    private static final Pattern REVISION_REQUEST = Pattern.compile(
            "(?is).*(加长|改长|变长|延长|加一点长度|加点长度|短一点|改短|变短|缩短|"
                    + "变瘦|改瘦|瘦一点|变窄|改窄|窄一点|变宽|改宽|宽一点|颜色改|改颜色|"
                    + "调整颜色|修改颜色|改.{0,4}版型|调整.{0,4}版型|版型.{0,4}(改|调整)|"
                    + "(修改|调整|优化|修正).{0,4}(衣长|裤长|袖长|边缘|轮廓|背景)|"
                    + "(衣长|裤长|袖长|边缘|轮廓|背景).{0,4}(修改|调整|优化|修正)|"
                    + "重新裁|裁一下|重新抠|重新提取|抠图改|继续改|再改).*"
    );
    private static final Pattern ORIGINAL_PHOTO_SOURCE = Pattern.compile(
            "(?is).*(原图|原始图片|原始照片|上传的照片|最初照片|重新抠|重新提取|重新裁).*"
    );
    private static final Pattern AMBIGUOUS_ORIGINAL_WORD = Pattern.compile("(?is).*原版.*");
    private static final Pattern CURRENT_DRAFT_SOURCE = Pattern.compile(
            "(?is).*(这一版|这版|当前版|最新版|刚才那版|刚生成的|继续改|再改|再短|再长|再窄|再宽).*"
    );
    private static final Pattern PREVIOUS_DRAFT_SOURCE = Pattern.compile("(?is).*(上一版|前一版).*");
    private static final Pattern NUMBERED_DRAFT_SOURCE = Pattern.compile(
            "(?is)(?:基于|按|用|从)?(?:第([一二三四五六七八九十])版|第?(\\d{1,2})版)"
    );
    private static final Set<String> SHORT_AFFIRMATIVES = Set.of(
            "嗯", "嗯嗯", "好", "好的", "好呀", "好哒", "可以", "可以的", "行", "行啊", "确认", "确认一下",
            "同意", "要", "就这个", "就它", "开始吧", "弄吧", "做吧", "没问题",
            "ok", "okay", "yes", "y", "yeah", "yep", "sure", "confirm"
    );
    private static final Pattern EXPLICIT_WARDROBE_CONFIRMATION = Pattern.compile(
            "(?is).*(确认|加入|放入|放进|放到|存入).*(衣橱|衣柜).*"
    );

    private final FashionWardrobeIngestionService ingestion;

    public FashionWardrobeDraftCommandHandler(FashionWardrobeIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Returns empty when the request is not explicit enough, or when several pending garments require a choice.
     * The caller then delegates the turn to the normal Agent flow.
     */
    public Optional<String> handle(String externalUserId, String message) {
        Optional<String> revision = handleExplicitRevision(externalUserId, message);
        if (revision.isPresent()) return revision;
        if (externalUserId == null || externalUserId.isBlank() || message == null) {
            return Optional.empty();
        }
        String request = message.strip();
        if (!isAffirmativeConfirmation(request)) return Optional.empty();

        List<ClothingCandidate> finalCandidates = ingestion.awaitingFinalConfirmationCandidates(externalUserId);
        List<ClothingCandidate> selectionCandidates = ingestion.pendingSelectionCandidates(externalUserId);
        if (finalCandidates.size() + selectionCandidates.size() != 1) return Optional.empty();
        if (finalCandidates.size() == 1) {
            ClothingCandidate candidate = finalCandidates.getFirst();
            ingestion.confirmCandidate(externalUserId, candidate.id());
            log.info("Fashion garment final confirmation submitted, user={}, candidate={}", anonymize(externalUserId), candidate.id());
            return Optional.of("这件衣物已加入你的衣橱。");
        }
        ClothingCandidate candidate = selectionCandidates.getFirst();
        ingestion.selectCandidatesForCutout(externalUserId, List.of(candidate.id()));
        log.info("Fashion garment cutout submitted from confirmation, user={}, candidate={}", anonymize(externalUserId), candidate.id());
        return Optional.of("已提交这件衣物的抠图草稿生成。完成后会自动把图片和属性发给你确认。");
    }

    /**
     * Executes only explicit garment-image revisions. This can run before the Agent because it never interprets a
     * short conversational acknowledgement as confirmation and asks instead of guessing when the source is unclear.
     */
    public Optional<String> handleExplicitRevision(String externalUserId, String message) {
        if (externalUserId == null || externalUserId.isBlank() || message == null) {
            return Optional.empty();
        }
        String request = message.strip();
        if (REVISION_REQUEST.matcher(request).matches()) {
            List<ClothingCandidate> candidates = ingestion.awaitingFinalConfirmationCandidates(externalUserId);
            if (candidates.size() != 1) return Optional.empty();
            ClothingCandidate candidate = candidates.getFirst();
            if (ORIGINAL_PHOTO_SOURCE.matcher(request).matches()) {
                ingestion.retryCutout(externalUserId, candidate.id(), request);
                log.info("Fashion garment recut submitted from original photo, user={}, candidate={}",
                        anonymize(externalUserId), candidate.id());
                return Optional.of("已基于原始上传照片重新生成衣物草稿。已有草稿会保留，完成后会自动发送新版本。");
            }
            List<GarmentDraftVersion> versions = ingestion.draftVersions(externalUserId, candidate.id());
            if (AMBIGUOUS_ORIGINAL_WORD.matcher(request).matches()) {
                return Optional.of(sourceQuestion(versions));
            }
            Integer requestedVersion = draftVersion(request);
            if (requestedVersion == null && PREVIOUS_DRAFT_SOURCE.matcher(request).matches()) {
                requestedVersion = previousDraftVersion(versions);
                if (requestedVersion == null) return Optional.of(sourceQuestion(versions));
            }
            if (requestedVersion != null) {
                ingestion.reviseDraft(externalUserId, candidate.id(), requestedVersion, request);
                log.info("Fashion garment draft revision submitted, user={}, candidate={}, sourceVersion={}",
                        anonymize(externalUserId), candidate.id(), requestedVersion);
                return Optional.of("已提交基于第" + requestedVersion + "版草稿的修改。旧版本会保留，完成后会自动发送新版本。");
            }
            if (CURRENT_DRAFT_SOURCE.matcher(request).matches()) {
                ingestion.reviseDraft(externalUserId, candidate.id(), null, request);
                log.info("Fashion garment draft revision submitted, user={}, candidate={}, sourceVersion=current",
                        anonymize(externalUserId), candidate.id());
                return Optional.of("已提交基于当前草稿的修改。旧版本会保留，完成后会自动发送新版本。");
            }
            return Optional.of(sourceQuestion(versions));
        }
        return Optional.empty();
    }

    private static String anonymize(String userId) {
        return Integer.toHexString(userId.hashCode());
    }

    /**
     * Short confirmations are intentionally accepted only after the caller proves there is one durable candidate.
     * This makes "OK" pleasant in chat without treating arbitrary messages as an irreversible wardrobe action.
     */
    private static boolean isAffirmativeConfirmation(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。.!！?？~～]+", "");
        if (normalized.isBlank() || normalized.contains("不要") || normalized.contains("别")
                || normalized.contains("不行") || normalized.contains("先不") || normalized.contains("算了")) {
            return false;
        }
        return SHORT_AFFIRMATIVES.contains(normalized)
                || EXPLICIT_WARDROBE_CONFIRMATION.matcher(normalized).matches();
    }

    private static Integer draftVersion(String request) {
        Matcher matcher = NUMBERED_DRAFT_SOURCE.matcher(request == null ? "" : request);
        if (!matcher.find()) return null;
        String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return switch (value) {
                case "一" -> 1;
                case "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> null;
            };
        }
    }

    private static Integer previousDraftVersion(List<GarmentDraftVersion> versions) {
        if (versions == null || versions.size() < 2) return null;
        int current = versions.stream().filter(GarmentDraftVersion::current)
                .mapToInt(GarmentDraftVersion::versionNumber).findFirst()
                .orElseGet(() -> versions.stream().mapToInt(GarmentDraftVersion::versionNumber).max().orElse(0));
        java.util.OptionalInt previous = versions.stream().mapToInt(GarmentDraftVersion::versionNumber)
                .filter(value -> value < current).max();
        return previous.isPresent() ? previous.getAsInt() : null;
    }

    private static String sourceQuestion(List<GarmentDraftVersion> versions) {
        int current = versions == null ? 0 : versions.stream().filter(GarmentDraftVersion::current)
                .mapToInt(GarmentDraftVersion::versionNumber).findFirst()
                .orElseGet(() -> versions.stream().mapToInt(GarmentDraftVersion::versionNumber).max().orElse(0));
        String draft = current > 0 ? "当前第" + current + "版草稿" : "已有草稿";
        return "这次要基于原始上传照片重新生成，还是基于" + draft + "继续修改？也可以直接说“基于第一版改”。";
    }
}
