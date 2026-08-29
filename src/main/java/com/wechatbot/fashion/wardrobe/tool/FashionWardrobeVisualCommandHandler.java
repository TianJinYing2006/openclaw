package com.wechatbot.fashion.wardrobe.tool;

import com.wechatbot.fashion.ai.service.AiChatService.AssistantAnswer;
import com.wechatbot.fashion.ai.model.ConversationMessage;
import com.wechatbot.fashion.wardrobe.domain.WardrobeSearchCriteria;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Guarantees a visual response for an explicit request to view the current whole wardrobe. */
@Component
@ConditionalOnBean(FashionVisualPreviewTools.class)
public class FashionWardrobeVisualCommandHandler {
    private static final Pattern WARDROBE_WORD = Pattern.compile("(?is).*(衣橱|衣柜).*");
    private static final Pattern VISUAL_WORD = Pattern.compile("(?is).*(图片|照片|展示|显示|发我看|给我看|看一下).*");
    private static final Pattern GENERIC_VISUAL_REQUEST = Pattern.compile(
            "(?is)^(?:帮我|请|麻烦)?(?:展示|显示|发我看|给我看|看一下)(?:下)?[。！？!?]*$");
    private final FashionVisualPreviewTools previews;

    public FashionWardrobeVisualCommandHandler(FashionVisualPreviewTools previews) {
        this.previews = previews;
    }

    /** Leaves filtered or ambiguous clothing queries to the normal Agent and its full filter tool schema. */
    public Optional<AssistantAnswer> handle(String externalUserId, String message) {
        return handle(externalUserId, message, List.of());
    }

    /** A short follow-up such as “帮我展示下” is safe only after the immediately preceding context mentioned wardrobe. */
    public Optional<AssistantAnswer> handle(
            String externalUserId, String message, List<ConversationMessage> recentConversation
    ) {
        String text = message == null ? "" : message.strip();
        boolean explicitWardrobeRequest = WARDROBE_WORD.matcher(text).matches() && VISUAL_WORD.matcher(text).matches();
        boolean contextualFollowUp = GENERIC_VISUAL_REQUEST.matcher(text).matches()
                && mentionsWardrobe(recentConversation);
        if (externalUserId == null || externalUserId.isBlank()
                || (!explicitWardrobeRequest && !contextualFollowUp)) {
            return Optional.empty();
        }
        FashionVisualPreviewTools.WardrobeDisplay display = previews.prepareWardrobeDisplay(
                externalUserId, WardrobeSearchCriteria.from(null, null, null, null, null, null, null, null), 8);
        return Optional.of(new AssistantAnswer(display.message(), display.artifacts()));
    }

    private static boolean mentionsWardrobe(List<ConversationMessage> values) {
        if (values == null || values.isEmpty()) return false;
        int start = Math.max(0, values.size() - 4);
        return values.subList(start, values.size()).stream()
                .map(ConversationMessage::text)
                .filter(value -> value != null)
                .anyMatch(value -> WARDROBE_WORD.matcher(value).matches());
    }
}
