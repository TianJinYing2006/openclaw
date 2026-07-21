# Java iLink 入门与运行指南

## 1. 项目现在完成了什么

当前 Spring Boot Demo 已经完成并实际验证：

- 从 Maven 下载 `weixin-ilink-sdk:1.0.0`
- 连接腾讯 `https://ilinkai.weixin.qq.com`
- 生成微信登录二维码
- 扫码后保存登录 session
- 使用 `getupdates` 长轮询接收消息
- 识别 TEXT、VOICE、IMAGE、FILE、VIDEO
- 对 TEXT 自动发送固定回复
- 保存消息游标并用消息 ID 降低重复回复
- 重启后恢复 session，无需再次扫码

## 2. 基础名词

| 名词 | 在本项目中的作用 |
|---|---|
| JDK | 编译和运行 Java；当前使用 Java 21 |
| Maven | 根据 `pom.xml` 下载 SDK、运行测试和打包 |
| Spring Boot | 启动应用、管理 iLink 服务生命周期、提供状态接口 |
| SDK | 封装二维码登录、长轮询和消息发送等协议细节 |
| iLink | Java 与腾讯微信服务器之间的 HTTP/JSON 通讯协议 |
| Session | 扫码成功后腾讯返回的登录凭证 |
| Cursor | `getupdates` 消息游标，表示消息读取位置 |
| context_token | 回复当前微信会话时必须携带的令牌 |

## 3. 新增代码说明

```text
src/main/java/com/example/ykdsummer/bot
├── config/ILinkProperties.java
│   └── 读取开关、腾讯地址、固定回复和 session 文件路径
├── controller/ILinkController.java
│   └── 提供状态、二维码和手动发送接口
├── message/ILinkMessageType.java
│   └── 把协议数字转换成 TEXT/VOICE/IMAGE/FILE/VIDEO
├── message/RecentMessageIds.java
│   └── 保存最近消息 ID，降低重复回复风险
├── runtime/ILinkRuntimeState.java
│   └── 保存可公开查看的连接状态和收发计数
├── service/ILinkBotService.java
│   └── 启动 SDK、监听消息、分类并固定回复文本
└── session/ILinkSessionStore.java
    └── 保存扫码 session 和长轮询 cursor
```

## 4. 第一次启动

在 PowerShell 中进入项目目录：

```powershell
Set-Location D:\YKD-summer
mvn package
$env:ILINK_ENABLED = "true"
$env:ILINK_FIXED_REPLY = "你好，我已经收到你的文本消息。"
java -jar .\target\ykd-summer-0.0.1-SNAPSHOT.jar --server.port=8081
```

保持这个 PowerShell 窗口运行，然后在浏览器打开：

- 状态：<http://127.0.0.1:8081/api/ilink/status>
- 二维码：<http://127.0.0.1:8081/api/ilink/qrcode>

看到二维码后，用手机微信扫码确认。状态从 `WAITING_FOR_QR_SCAN` 变为 `CONNECTED` 即表示成功。

正常停止程序使用 `Ctrl+C`。

## 5. 测试文本收发

在微信 ClawBot 对话中发送：

```text
你好，测试 Java iLink
```

Java 日志会出现收到 TEXT 的记录，微信收到配置的固定回复。状态接口中的 `receivedMessages` 和 `sentMessages` 会增加。

## 6. 修改固定回复

启动前设置不同的环境变量：

```powershell
$env:ILINK_FIXED_REPLY = "收到，我是 Java 机器人。"
```

也可以修改 `src/main/resources/application.properties` 中的 `ilink.fixed-reply` 默认值。

## 7. 登录凭证安全

扫码后凭证保存在：

```text
D:\YKD-summer\.ilink\session.properties
```

该文件包含 token：

- 不要截图或发送给其他人
- 不要提交到 Git
- `.ilink/` 已加入 `.gitignore`
- 不要在程序运行时手动编辑

session 过期时 SDK 会清理并生成新二维码。需要主动重新登录时，先停止程序，再删除 `.ilink/session.properties`，然后重新启动并扫码。

## 8. 常用命令

```powershell
# 运行测试
mvn test

# 打包
mvn package

# 临时禁用 iLink，只运行普通 Spring 功能
$env:ILINK_ENABLED = "false"
java -jar .\target\ykd-summer-0.0.1-SNAPSHOT.jar --server.port=8081
```

### 语音合成（TTS）

机器人只在用户手打 `语音：问题` 时进入语音输出分支：先调用大模型生成简短回答，
再调用阿里云百炼 TTS，最后只发送一个 `answer.mp3` 文件。微信发来的语音转写
仍按普通问题处理，不会误触发这个命令。

启动前需要配置：

```powershell
$env:ALIYUN_TTS_ENABLED = "true"
$env:DASHSCOPE_API_KEY = "你的百炼 API Key"
$env:ALIYUN_TTS_BASE_URL = "https://你的工作空间.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
$env:ALIYUN_TTS_MODEL = "cosyvoice-v3-flash"
$env:ALIYUN_TTS_VOICE = "longanyang"
```

`ALIYUN_TTS_VOICE` 只设置默认音色。机器人运行后可以直接在微信中按用户实时切换，
不需要修改环境变量或重启 Spring Boot：

```text
音色列表
设置音色：龙婉
当前音色
语音：介绍一下 Spring AI
重置音色
```

也支持“换音色：龙婉”“切换音色：龙婉”和“更换音色：龙婉”。当前从
`cosyvoice-v3-flash` 官方音色中内置 16 种常用选项：龙安洋、龙安欢、龙呼呼、力豆、
龙飞、龙应聆、龙小淳、龙安昀、龙安温、龙安朗、龙婉、龙安柔、龙安智、龙安雅、
龙老铁和龙安粤。不同微信用户的选择互不影响。
选择结果目前只保存在 Java 进程内存中，应用重启后恢复 `ALIYUN_TTS_VOICE` 指定的默认音色。
默认音色为 `longanyang`。`cosyvoice-v3-flash` 已使用当前百炼工作空间完成真实接口验证，
成功返回 MP3 文件。

## 9. 文件处理与生成

当前复刻 TJY 的一次性文件会话，不再使用 wangentong 的文档模式、版本和撤销状态机：

1. 先发送一个文件，机器人回复“已收到文件，请告诉我怎么处理”；
2. 5 分钟内直接发送普通话要求，例如“总结重点”“修改第二段”“帮我把这个变成 PDF”；
3. Java 把文件正文和要求一起交给模型。普通分析返回文字；模型返回内部 `FILE_GEN||JSON` 时，Java 生成并发送文件；
4. 文件只供下一条文字使用，处理后立即清除。要再次基于原文件处理，需要重新上传。

用户不需要输入 `FILE_GEN`、`分析：`、`修改：` 或 `生成：` 等前缀，也没有“当前文件、撤销、使用原版、完成、关闭文件”等命令。
不上传文件也可以直接说“写一份 Word 周报”“生成一份 PDF 报告”“创建 Excel 表格”。当前输出格式支持
DOCX、XLSX、PDF 和 TXT。

Java 会优先提取 TXT、PDF、DOCX、XLSX、PPTX 等文件的正文。无法本地提取时才把原始文件交给
Responses 文件输入。生成和修改属于内容级重建，不保证无损保留原 Word/Excel/PDF 的复杂样式、图片、公式或版式。
文件缓存仅存在当前 Java 进程内，5 分钟过期，发送 `清空` 或重启应用也会清除。

## 10. AI 协议路由

- 普通纯文本：使用 Spring AI 1.1.8 调用 `/v1/chat/completions`，并携带 system、历史 USER/ASSISTANT 和当前问题；这条路线用于后续 Agent/Tool。
- 图片、文件、视频帧：继续使用已经验证的 Responses 协议，保留 `input_image`、`input_file` 和 reasoning 能力。
- 文件正文可本地提取时：隐藏的 `FILE_GEN` 指令走 Spring AI Chat Completions；无法提取而必须附带二进制文件时回退 Responses。
- 两条模型请求都设置 `store=false`；短期上下文仍由 Java 按用户隔离保存在内存中，重启后清空。

Spring AI 默认使用 `SPRING_AI_BASE_URL=https://moosecloud.cc` 和 `/v1/chat/completions`，密钥默认复用 `AI_API_KEY`；如需与 Responses 分开，可设置 `SPRING_AI_API_KEY`、`SPRING_AI_BASE_URL` 和 `SPRING_AI_MODEL`。

## 11. 当前能力边界

- 文本：支持固定命令和 Spring AI Chat Completions 多轮对话，默认采用自然、直接、简洁的微信口吻
- 语音输入：有腾讯转写文字时进入大模型；没有转写时提示改发文字
- 语音输出：`语音：问题` 只返回 MP3 文件；支持按用户实时切换音色；不是微信原生语音气泡
- 图片：支持 CDN 下载解密并交给视觉模型理解
- 视频：支持 60 秒、20 MiB 以内的短视频固定抽取 10 帧，并用腾讯云 ASR 转写人声后联合理解
- 文件输入：一次只接收 1 个，最大 20 MiB；缓存 5 分钟并供下一条普通话指令使用
- 文件输出：支持 DOCX、XLSX、PDF、TXT；内容由模型生成后交给 Java 渲染，二进制不进入聊天历史
- 幂等：当前为内存窗口；生产环境建议改成 Redis
- Java SDK：社区实现，生产使用前需要继续审计并跟踪腾讯官方插件协议变化
