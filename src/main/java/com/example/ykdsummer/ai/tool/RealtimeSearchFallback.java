package com.example.ykdsummer.ai.tool;

import org.springframework.stereotype.Component;

/** Uses the configured Bocha source when a specialized real-time data source is unavailable. */
@Component
public class RealtimeSearchFallback {

    private final BochaWebSearchTools bochaWebSearchTools;

    public RealtimeSearchFallback(BochaWebSearchTools bochaWebSearchTools) {
        this.bochaWebSearchTools = bochaWebSearchTools;
    }

    static RealtimeSearchFallback unavailable() {
        return new RealtimeSearchFallback(null);
    }

    public String search(String capability, String query, String reason) {
        if (bochaWebSearchTools == null) {
            return capability + "暂时不可用：" + reason + "，且博查搜索未配置。";
        }
        String result = bochaWebSearchTools.searchWeb(query);
        if (result.startsWith("博查搜索结果：")) {
            return capability + "专用服务" + reason + "，已自动切换到博查公开网页搜索；"
                    + "结果仅供参考，请以交易平台或官方渠道为准。\n\n" + result;
        }
        return capability + "暂时不可用：" + reason + "。博查搜索结果：" + result;
    }
}
