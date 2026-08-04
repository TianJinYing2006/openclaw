package com.example.ykdsummer.ai.tool;

/** Replaceable web search port so the fallback path is agnostic to the underlying provider. */
public interface WebSearchProvider {
    /** 执行网页搜索，返回格式化结果字符串。成功时以 "搜索结果：" 开头，失败时返回错误说明。 */
    String search(String query);
}
