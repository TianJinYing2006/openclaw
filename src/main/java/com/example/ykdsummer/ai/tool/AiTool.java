package com.example.ykdsummer.ai.tool;

/**
 * 标记接口：供工具注册表记录一组相关的 {@code @Tool} 方法。
 *
 * <p>实际暴露给模型的 Tool 仍由
 * {@link com.example.ykdsummer.ai.service.SpringAiChatCompletionsGateway} 显式选择，
 * 以保护图片、文件等需要微信回传生命周期的既有实现不被同名旧 Tool 覆盖。</p>
 */
public interface AiTool {
}
