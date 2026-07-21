# 语音支持规范

## 概述

在现有语音接收（ASR 识别）和语音回复（TTS 合成）基础上，增加原始语音文件的主动发送能力，提供与图片发送一致的 REST 端点和管理界面。

## 数据流

```
用户/运维 → POST /api/message/sendVoice (multipart)
                → ILinkService.sendVoiceWithTyping(to, bytes, fileName, playTimeMs, sampleRate)
                    → client.sendTextWithTyping(caption)
                    → client.sendVoice(bytes, fileName, playTimeMs, sampleRate)
                        → 微信用户收到语音消息
```

## REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/message/sendVoice | multipart 上传音频文件，发送语音 |
| GET | /api/message/sendVoice | HTML 表单页面，用于浏览器手动测试 |

## 依赖

- `ILinkService.sendVoice()` — 已实现，底层 SDK 调用
- `SpeechSynthesisService` — 用于 TTS 语音回复（已有 `/api/message/sendVoiceReply`）
