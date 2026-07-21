package com.example.demo.chat;

import com.example.demo.model.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionManager {

    private final ConcurrentHashMap<String, Deque<Message>> sessionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> voicePreferenceMap = new ConcurrentHashMap<>();
    private static final int MAX_ROUNDS = 20;
    private static final int MAX_MESSAGES = MAX_ROUNDS * 2;

    public void addMessage(String userId, String role, String content) {
        Deque<Message> deque = sessionMap.computeIfAbsent(userId, key -> new LinkedList<>());
        deque.addLast(new Message(role, content));
        while (deque.size() > MAX_MESSAGES) {
            deque.removeFirst();
        }
    }

    public List<Message> getHistory(String userId) {
        Deque<Message> deque = sessionMap.get(userId);
        if (deque == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(deque);
    }

    public void setVoicePreference(String userId, String voice) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (voice == null || voice.isBlank()) {
            voicePreferenceMap.remove(userId);
            return;
        }
        voicePreferenceMap.put(userId, voice.trim());
    }

    public String getVoicePreference(String userId) {
        return voicePreferenceMap.get(userId);
    }

    public void clearSession(String userId) {
        sessionMap.remove(userId);
        voicePreferenceMap.remove(userId);
    }

    public int getActiveSessionCount() {
        Set<String> userIds = new HashSet<>(sessionMap.keySet());
        userIds.addAll(voicePreferenceMap.keySet());
        return userIds.size();
    }

    public boolean hasSession(String userId) {
        return sessionMap.containsKey(userId) || voicePreferenceMap.containsKey(userId);
    }
}