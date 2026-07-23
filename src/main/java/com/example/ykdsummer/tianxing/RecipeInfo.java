package com.example.ykdsummer.tianxing;

import java.util.List;

/**
 * 菜谱搜索结果或详情，不暴露第三方接口的原始 JSON。
 */
public record RecipeInfo(
        Integer id,
        String name,
        String typeName,
        String description,       // 菜肴特性
        String ingredients,       // 原料
        String seasonings,        // 调料
        String steps,             // 做法步骤
        String tip               // 提示
) {
    /**
     * 从搜索列表项构建精简版（不含步骤和提示）。
     */
    public static RecipeInfo fromListItem(Integer id, String name, String typeName, String description) {
        return new RecipeInfo(id, name, typeName, description, null, null, null, null);
    }

    /**
     * 格式化输出，适配 AI 返回给用户。
     */
    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("🍳 ").append(name);
        if (typeName != null && !typeName.isBlank()) {
            sb.append("（").append(typeName).append("）");
        }
        sb.append('\n');
        if (description != null && !description.isBlank()) {
            sb.append("特点：").append(description).append('\n');
        }
        if (ingredients != null && !ingredients.isBlank()) {
            sb.append("原料：").append(ingredients).append('\n');
        }
        if (seasonings != null && !seasonings.isBlank()) {
            sb.append("调料：").append(seasonings).append('\n');
        }
        if (steps != null && !steps.isBlank()) {
            sb.append("做法：\n").append(steps).append('\n');
        }
        if (tip != null && !tip.isBlank()) {
            sb.append("💡 ").append(tip);
        }
        return sb.toString();
    }

    /**
     * 搜索列表的紧凑格式。
     */
    public static String formatList(List<RecipeInfo> items) {
        if (items == null || items.isEmpty()) {
            return "没有找到相关菜谱。";
        }
        var sb = new StringBuilder("找到以下菜谱：\n");
        for (int i = 0; i < items.size(); i++) {
            var r = items.get(i);
            sb.append(i + 1).append(". ").append(r.name());
            if (r.typeName() != null && !r.typeName().isBlank()) {
                sb.append("（").append(r.typeName()).append("）");
            }
            sb.append('\n');
        }
        sb.append("\n回复“菜谱详情 + 编号”查看完整做法。");
        return sb.toString();
    }
}
