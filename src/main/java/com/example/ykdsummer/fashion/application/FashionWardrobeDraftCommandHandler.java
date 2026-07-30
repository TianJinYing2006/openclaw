package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
            "(?is).*(加长|改长|变长|延长|加一点长度|加点长度|短一点|改短|变短|缩短|衣长|裤长|袖长|"
                    + "颜色改|改颜色|边缘|重新裁|裁一下|重新抠|抠图改).*"
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
        if (externalUserId == null || externalUserId.isBlank() || message == null) {
            return Optional.empty();
        }
        String request = message.strip();
        if (REVISION_REQUEST.matcher(request).matches()) {
            List<ClothingCandidate> candidates = ingestion.awaitingFinalConfirmationCandidates(externalUserId);
            if (candidates.size() != 1) return Optional.empty();
            ClothingCandidate candidate = candidates.getFirst();
            ingestion.reviseDraft(externalUserId, candidate.id(), null, request);
            log.info("Fashion garment draft revision submitted, user={}, candidate={}", anonymize(externalUserId), candidate.id());
            return Optional.of("已提交这件衣物的新草稿修改。原版会保留，生成完成后会自动把新版本图片发给你确认。");
        }
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
}
