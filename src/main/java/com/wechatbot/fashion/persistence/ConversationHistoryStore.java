package com.wechatbot.fashion.persistence;

import com.wechatbot.fashion.ai.model.ConversationMessage;
import java.util.List;

public interface ConversationHistoryStore {
    List<ConversationMessage> load(String userId, int limit);

    void appendTurn(String userId, ConversationMessage userMessage, ConversationMessage assistantMessage);

    void clear(String userId);

    static ConversationHistoryStore disabled() {
        return Disabled.INSTANCE;
    }

    enum Disabled implements ConversationHistoryStore {
        INSTANCE;

        @Override public List<ConversationMessage> load(String userId, int limit) { return List.of(); }
        @Override public void appendTurn(String userId, ConversationMessage userMessage, ConversationMessage assistantMessage) { }
        @Override public void clear(String userId) { }
    }
}
