package com.example.ykdsummer.ai.fashion.look.rag;

import com.example.ykdsummer.ai.fashion.look.model.RetrievedChunk;
import com.example.ykdsummer.ai.fashion.look.model.SeedEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalDiversitySamplerTest {

    private static RetrievedChunk chunk(String id, double score) {
        return new RetrievedChunk(new SeedEntry(id, "test", null, null, null, null, null, null, null), score);
    }

    @Test
    void keepsOrderAndCountWhenPoolSmallerThanK() {
        List<RetrievedChunk> pool = List.of(chunk("a", 0.9), chunk("b", 0.5));
        List<RetrievedChunk> result = RetrievalDiversitySampler.sample(pool, 5, 0.0);
        assertEquals(List.of("a", "b"), result.stream().map(c -> c.entry().id()).toList());
    }

    @Test
    void returnsExactKFromLargerPool() {
        List<RetrievedChunk> pool = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pool.add(chunk("id" + i, 0.9 - i * 0.01));
        }
        List<RetrievedChunk> result = RetrievalDiversitySampler.sample(pool, 5, 0.0);
        assertEquals(5, result.size());
        assertTrue(pool.containsAll(result));
        assertEquals(5, result.stream().map(c -> c.entry().id()).distinct().count());
    }

    @Test
    void filtersBelowMinScore() {
        List<RetrievedChunk> pool = List.of(
                chunk("a", 0.9), chunk("b", 0.8), chunk("c", 0.1), chunk("d", 0.05));
        List<RetrievedChunk> result = RetrievalDiversitySampler.sample(pool, 2, 0.5);
        assertEquals(List.of("a", "b"), result.stream().map(c -> c.entry().id()).toList());
    }

    @Test
    void nullPoolReturnsEmpty() {
        assertTrue(RetrievalDiversitySampler.sample(null, 5, 0.0).isEmpty());
    }
}
