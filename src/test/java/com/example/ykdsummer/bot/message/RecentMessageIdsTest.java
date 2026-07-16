package com.example.ykdsummer.bot.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentMessageIdsTest {

    @Test
    void remembersIdsAndEvictsTheOldestOne() {
        RecentMessageIds ids = new RecentMessageIds(2);

        ids.remember(10L);
        ids.remember(20L);
        assertTrue(ids.contains(10L));
        assertTrue(ids.contains(20L));

        ids.remember(30L);
        assertFalse(ids.contains(10L));
        assertTrue(ids.contains(20L));
        assertTrue(ids.contains(30L));
    }
}
