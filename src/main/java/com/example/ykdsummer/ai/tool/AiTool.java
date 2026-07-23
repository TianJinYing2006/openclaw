package com.example.ykdsummer.ai.tool;

/**
 * 标记接口：所有通过 {@code @Tool} 暴露给 AI 模型的工具类都应实现此接口。
 *
 * <p>{@link com.example.ykdsummer.ai.service.SpringAiChatCompletionsGateway}
 * 会自动收集所有 {@code AiTool} Bean 并注册到 {@code ChatClient}，
 * 新增工具只需 {@code implements AiTool}，网关无需改动。</p>
 */
public interface AiTool {
}
