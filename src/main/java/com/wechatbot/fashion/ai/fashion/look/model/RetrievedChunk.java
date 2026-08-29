package com.wechatbot.fashion.ai.fashion.look.model;

/**
 * RAG 检索返回的单条知识片段，包装了种子数据条目和匹配分数。
 */
public record RetrievedChunk(SeedEntry entry, double score) {

    /** 转为 Agent prompt 可用的简洁文本。 */
    public String toPromptText() {
        SeedEntry.Outfit o = entry.outfit();
        SeedEntry.Tags t = entry.tags();
        StringBuilder sb = new StringBuilder();
        sb.append("- [").append(entry.id()).append("] ");
        if (entry.summary() != null) sb.append(entry.summary());
        sb.append("\n  搭配: ");
        if (o != null) {
            sb.append("上装=").append(o.top() != null ? o.top() : "无")
              .append(", 下装=").append(o.bottom() != null ? o.bottom() : "无")
              .append(", 鞋=").append(o.shoes() != null ? o.shoes() : "无");
            if (o.accessories() != null) sb.append(", 配饰=").append(o.accessories());
        }
        if (t != null) {
            sb.append("\n  标签: ");
            if (t.style() != null) sb.append("风格=").append(String.join("/", t.style())).append(" ");
            if (t.scene() != null) sb.append("场景=").append(String.join("/", t.scene())).append(" ");
            if (t.season() != null) sb.append("季节=").append(String.join("/", t.season()));
            if (t.colorScheme() != null) sb.append(" 配色=").append(t.colorScheme());
        }
        sb.append("\n  来源: ").append(entry.source() != null ? entry.source() : "未知");
        return sb.toString();
    }
}
