package com.wechatbot.fashion.ai.service;

/**
 * 标记需要 OpenAI Responses 协议的网关。
 *
 * <p>文件、图片、视频帧和文档任务依赖 Responses 的 input_file/input_image/reasoning，
 * 路由器通过这个窄接口明确选择协议，避免与普通 Completion 混在一起。</p>
 */
public interface ResponsesGateway extends LlmGateway {
}
