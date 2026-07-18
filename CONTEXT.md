# Demo1 — 微信公众号 Clawbot

基于 ILink SDK 连接微信公众号，通过 LLM 提供智能对话的聊天机器人服务。

## Language

**Clawbot**:
微信公众号聊天机器人，接收用户消息，通过 LLM 处理后返回回复。
_Avoid_: Bot, chatbot, 机器人

**ILink**:
微信官方 ILink 协议，用于第三方服务与微信公众号之间的消息通信。
_Avoid_: SDK, 微信 SDK

**Command**:
以 `/` 前缀开头的特殊消息，触发预定义功能（如 `/help`、`/version`），不经过 LLM 处理。
_Avoid_: 指令, 命令（中文）

**Session**:
与单个微信用户的多轮对话上下文，保留最近 20 轮（用户+助手交替）历史消息，用于 LLM 理解对话连续性。
_Avoid_: 会话, 对话历史, conversation

**LLM**:
运行在阿里云百炼平台的 `qwen-plus` 大语言模型，通过 OpenAI 兼容 API 调用。
_Avoid_: AI, 模型, 大模型

**User**:
通过微信公众号向 Clawbot 发送消息的微信用户，以 `from_user_id` 唯一标识。
_Avoid_: 用户, 微信用户, customer

**Message**:
用户发送的文本或图片，通过 ILink SDK 以 `WeixinMessage` 形式到达。
_Avoid_: 消息, 文本
