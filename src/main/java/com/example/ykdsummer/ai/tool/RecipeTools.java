package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.tianxing.RecipeInfo;
import com.example.ykdsummer.tianxing.RecipeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI 暴露给模型的菜谱工具边界。
 */
@Component
public class RecipeTools implements AiTool {

    private final RecipeService recipeService;

    public RecipeTools(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @Tool(
            name = "search_recipe",
            description = "搜索菜谱，根据菜名关键词查找相关菜谱列表。返回菜名和分类，不含做法步骤。"
    )
    public String searchRecipe(
            @ToolParam(required = true, description = "菜名关键词，例如：红烧肉、番茄炒蛋、麻婆豆腐")
            String keyword,
            @ToolParam(required = false, description = "返回数量，默认5，最大10")
            Integer num
    ) {
        int count = (num != null) ? num : 5;
        List<RecipeInfo> results = recipeService.searchRecipe(keyword, count);
        return RecipeInfo.formatList(results);
    }

    @Tool(
            name = "get_recipe_detail",
            description = "获取完整菜谱详情，包含原料、调料、详细做法步骤和提示。必须先调用 search_recipe 获得菜品 ID。"
    )
    public String getRecipeDetail(
            @ToolParam(required = true, description = "菜品 ID，从 search_recipe 的结果中获取")
            int id
    ) {
        RecipeInfo detail = recipeService.getRecipeDetail(id);
        return detail.toString();
    }
}
