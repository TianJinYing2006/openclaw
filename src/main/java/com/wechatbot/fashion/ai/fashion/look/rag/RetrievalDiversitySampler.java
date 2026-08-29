package com.wechatbot.fashion.ai.fashion.look.rag;

import com.wechatbot.fashion.ai.fashion.look.model.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 检索结果多样性采样器。
 *
 * <p>在相关性排序后的候选池内做"加权随机不放回"抽样：相关度越高的候选中选
 * 概率越大（权重取 score² 放大高分段差异，并保留小常数兜底），从而在保持
 * 推荐准确度的前提下，让不同次检索命中不同的参考穿搭，降低跨次重复率。
 */
public final class RetrievalDiversitySampler {

    private RetrievalDiversitySampler() {}

    /**
     * 从候选池中按相关度加权随机抽取 {@code k} 条。
     *
     * @param pool      按相关度降序的候选池
     * @param k         抽取条数
     * @param minScore  相关度下限（低于该分的候选不参与抽样；0 表示不限制）
     * @return 抽样结果（保持池内相对顺序），池小于等于 k 时原样返回
     */
    public static List<RetrievedChunk> sample(List<RetrievedChunk> pool, int k, double minScore) {
        if (pool == null || pool.size() <= k) {
            return pool == null ? List.of() : pool;
        }
        List<RetrievedChunk> eligible = new ArrayList<>();
        for (RetrievedChunk chunk : pool) {
            if (chunk.score() >= minScore) {
                eligible.add(chunk);
            }
        }
        if (eligible.size() <= k) {
            return eligible;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<RetrievedChunk> remaining = new ArrayList<>(eligible);
        List<RetrievedChunk> result = new ArrayList<>(k);
        while (result.size() < k) {
            double totalWeight = 0;
            for (RetrievedChunk chunk : remaining) {
                totalWeight += weight(chunk.score());
            }
            double pick = rnd.nextDouble() * totalWeight;
            double acc = 0;
            int chosen = 0;
            for (int i = 0; i < remaining.size(); i++) {
                acc += weight(remaining.get(i).score());
                if (pick <= acc) {
                    chosen = i;
                    break;
                }
            }
            result.add(remaining.remove(chosen));
        }
        return result;
    }

    /** score² 放大高分段差异 + 小常数兜底（避免全零权重）。 */
    private static double weight(double score) {
        double s = Math.max(0.0, Math.min(1.0, score));
        return s * s + 0.01;
    }
}
