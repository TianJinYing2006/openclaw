package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.search.FetchedPage;
import com.example.ykdsummer.search.SearchResult;
import com.example.ykdsummer.search.WebSearchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI 暴露给模型的联网搜索与网页抓取工具。
 */
@Component
public class WebSearchTools implements AiTool {

    private final WebSearchService webSearchService;

    public WebSearchTools(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    @Tool(
            name = "search_web",
            description = "使用 Brave Search 在互联网上搜索最新信息。"
                    + "当用户询问实时新闻、最新动态、你不确定的事实问题时，请使用此工具获取最新信息。"
                    + "返回标题、链接和摘要。"
    )
    public String searchWeb(
            @ToolParam(required = true, description = "搜索关键词，用中文描述即可")
            String query,
            @ToolParam(required = false, description = "返回结果数量，默认 5，最多 10")
            Integer count
    ) {
        int n = count != null ? count : 5;
        List<SearchResult> results = webSearchService.search(query, n);
        return SearchResult.formatList(results);
    }

    @Tool(
            name = "fetch_web_page",
            description = "抓取指定 URL 的网页正文内容，返回纯文本。"
                    + "与 search_web 配合使用：先搜索得到 URL，再抓取页面获取详细内容。"
    )
    public FetchedPage fetchWebPage(
            @ToolParam(required = true, description = "网页完整 URL，需带 http:// 或 https:// 前缀")
            String url
    ) {
        return webSearchService.fetchPage(url);
    }
}
