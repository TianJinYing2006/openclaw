package com.wechatbot.fashion.tianxing;

/**
 * 星座运势结果。
 */
public record HoroscopeInfo(
        String type,
        String content
) {
    @Override
    public String toString() {
        return String.format("""
                ⭐ %s
                %s""",
                type != null ? type : "星座运势",
                content != null ? content : "暂无数据"
        );
    }
}
