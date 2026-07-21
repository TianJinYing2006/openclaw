package com.example.demo.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @Test
    void storesAndClearsVoicePreferenceWithSession() {
        SessionManager sessionManager = new SessionManager();

        sessionManager.setVoicePreference("user-1", "Cherry");

        assertEquals("Cherry", sessionManager.getVoicePreference("user-1"));
        assertTrue(sessionManager.hasSession("user-1"));
        assertEquals(1, sessionManager.getActiveSessionCount());

        sessionManager.clearSession("user-1");

        assertFalse(sessionManager.hasSession("user-1"));
        assertEquals(null, sessionManager.getVoicePreference("user-1"));
    }
}