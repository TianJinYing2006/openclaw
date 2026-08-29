package com.wechatbot.fashion.persistence;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedInstanceScopeTest {
    @Test
    void extractsOnlyTheManagedInstancePrefix() {
        String instanceId = UUID.randomUUID().toString();

        ManagedInstanceScope scope = ManagedInstanceScope.parse("managed:" + instanceId + ":wechat-user");

        assertTrue(scope.managed());
        assertEquals(instanceId, scope.instanceId());
    }

    @Test
    void rejectsIncompleteOrMalformedNamespaces() {
        assertFalse(ManagedInstanceScope.parse("plain-user").managed());
        assertFalse(ManagedInstanceScope.parse("managed:not-a-uuid:wechat-user").managed());
        assertFalse(ManagedInstanceScope.parse("managed:" + UUID.randomUUID()).managed());
        assertNull(ManagedInstanceScope.parse(null).instanceId());
    }
}
