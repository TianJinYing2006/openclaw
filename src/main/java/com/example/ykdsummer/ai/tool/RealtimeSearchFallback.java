package com.example.ykdsummer.ai.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Uses the configured web search source when a specialized real-time data source is unavailable. */
@Component
public class RealtimeSearchFallback {

    private final ObjectProvider<WebSearchProvider> webSearchProvider;

    public RealtimeSearchFallback(ObjectProvider<WebSearchProvider> webSearchProvider) {
        this.webSearchProvider = webSearchProvider;
    }

    static RealtimeSearchFallback unavailable() {
        return new RealtimeSearchFallback(null);
    }

    public String search(String capability, String query, String reason) {
        WebSearchProvider provider = webSearchProvider == null ? null : webSearchProvider.getIfAvailable();
        if (provider == null) {
            return capability + "暂时不可用：" + reason + "，且搜索服务未配置。";
        }
        String result = provider.search(query);
        // MCP provider 使用 "搜索结果："；博查实现保持旧前缀 "博查搜索结果："，两者都视为成功。
        if (result.startsWith("搜索结果：") || result.startsWith("博查搜索结果：")) {
            return capability + "专用服务" + reason + "，已自动切换到公开网页搜索；"
                    + "结果仅供参考，请以交易平台或官方渠道为准。\n\n" + result;
        }
        return capability + "暂时不可用：" + reason + "。搜索结果：" + result;
    }
}
