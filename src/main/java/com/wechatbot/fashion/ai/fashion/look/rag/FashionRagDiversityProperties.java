package com.wechatbot.fashion.ai.fashion.look.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 穿搭知识检索多样性配置。
 *
 * <p>通过 {@code app.fashion.rag.diversity.*} 前缀注入，作用于所有检索通道
 * （RAGFlow / MySQL FTS）。思路是"相关性优先、多样性其次"：先按相关度取
 * 较大的候选池，再在池内做加权随机（高分命中概率高），既打破跨次推荐的重复，
 * 又不牺牲与用户需求的匹配度。
 */
@ConfigurationProperties(prefix = "app.fashion.rag.diversity")
public class FashionRagDiversityProperties {

    /** 是否启用多样性采样。关闭后恢复确定性 top-k 检索。 */
    private boolean enabled = true;

    /** 候选池大小：按相关度取前 N 条后再随机挑返回条数，必须不小于返回条数。 */
    private int candidatePool = 15;

    /** 随机池相关度下限（0~1，仅 RAGFlow 的 0~1 相似度有意义；0 表示不限制）。 */
    private double minScore = 0.0;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getCandidatePool() { return candidatePool; }
    public void setCandidatePool(int candidatePool) { this.candidatePool = candidatePool; }

    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
}
