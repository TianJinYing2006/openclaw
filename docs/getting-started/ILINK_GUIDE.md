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

不需要再写 `语音：` 前缀。用户可以直接说“请用语音回答”“朗读一下这段内容”或
“换成女声后解释 iLink”。纯文本会先进入 Spring AI；模型根据 `synthesize_speech`、
`set_voice` 等工具的说明决定是否调用。TTS 成功后，机器人只发送 `answer.mp3` 文件。
微信发来的语音转写本身仍按普通问题处理。

启动前需要配置：

```powershell
$env:ALIYUN_TTS_ENABLED = "true"
$env:DASHSCOPE_API_KEY = "你的百炼 API Key"
$env:ALIYUN_TTS_BASE_URL = "https://你的工作空间.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
$env:ALIYUN_TTS_MODEL = "cosyvoice-v3-flash"
$env:ALIYUN_TTS_VOICE = "longanyang"
```

`ALIYUN_TTS_VOICE` 只设置默认音色。机器人运行后可以直接在微信中自然表达需求，
模型会通过音色设置工具按用户实时切换，不需要修改环境变量或重启 Spring Boot：

```text
有哪些音色？
换成龙婉
现在用的什么音色？
请用语音介绍一下 Spring AI
恢复默认音色
```

也支持“换音色：龙婉”“切换音色：龙婉”和“更换音色：龙婉”。当前从
`cosyvoice-v3-flash` 官方音色中内置 16 种常用选项：龙安洋、龙安欢、龙呼呼、力豆、
龙飞、龙应聆、龙小淳、龙安昀、龙安温、龙安朗、龙婉、龙安柔、龙安智、龙安雅、
龙老铁和龙安粤。不同微信用户的选择互不影响。
选择结果目前只保存在 Java 进程内存中，应用重启后恢复 `ALIYUN_TTS_VOICE` 指定的默认音色。
默认音色为 `longanyang`。`cosyvoice-v3-flash` 已使用当前百炼工作空间完成真实接口验证，
成功返回 MP3 文件。

## 9. 文件处理、版本与生成

用户无需输入任何隐藏前缀。上传文件后可以直接说“总结重点”“把第二段改得正式一些”“转成 PDF”或
“回到第 1 版”。文件会先登记为当前用户独立的 `doc_* v1` 本地资产；模型从工具说明中自行判断要分析、创建、修改、转换还是回退。

1. 收到文件时，iLink SDK 下载并解密；如果这条消息没有要求，文件会短暂等待下一条普通话指令（5 分钟）。
2. 真正处理时，Java 保存不可覆盖的文档资产；能提取正文时把 `assetId`、版本和正文交给 Spring AI 工具编排。
3. 模型调用 `get_current_document` 查询，再按需要调用 `create_document`、`replace_document_content` 或 `restore_document_version`。
4. 创建、修改和回退都会新建版本而不是覆盖旧版本；回退 v1 的结果也会成为新的 vN。实际文件随后由 iLink 发送给微信。

不上传文件也能直接说“写一份 Word 周报”“生成一份 PDF 报告”“创建 Excel 表格”。目前输出支持 DOCX、XLSX、PDF 和 TXT。
生成和修改属于内容级重新渲染，不保证无损保留原 Word/Excel/PDF 的复杂样式、图片、公式或版式。
聊天记忆和 5 分钟待处理会话会随进程重启清除；已登记的文档版本保存在 `.ai-assets/documents/`，不会因重启丢失。

## 10. AI 协议路由

- 普通纯文本：使用 Spring AI 1.1.8 调用 `/v1/chat/completions`，并携带 system、历史 USER/ASSISTANT 和当前问题；这条路线用于后续 Agent/Tool。
- 图片：先登记为 `img_*`，再进入 Spring AI 工具编排；模型需要真实画面时由 `inspect_image` 使用 Responses 的 `input_image` 读取对应本地原图。
- 无法本地提取的文件、视频帧：继续使用已经验证的 Responses 协议，保留 `input_image`、`input_file` 和 reasoning 能力。
- 文件正文可本地提取时：走 Spring AI Chat Completions，并由模型调用 `DocumentTools`；无法提取而必须附带二进制文件时回退 Responses 做理解。
- 两条模型请求都设置 `store=false`；短期上下文仍由 Java 按用户隔离保存在内存中，重启后清空。

Spring AI 默认使用 `SPRING_AI_BASE_URL=https://moosecloud.cc` 和 `/v1/chat/completions`，密钥默认复用 `AI_API_KEY`；如需与 Responses 分开，可设置 `SPRING_AI_API_KEY`、`SPRING_AI_BASE_URL` 和 `SPRING_AI_MODEL`。

## 11. 当前能力边界

- 文本：支持固定命令和 Spring AI Chat Completions 多轮对话，默认采用自然、直接、简洁的微信口吻
- 语音输入：有腾讯转写文字时进入大模型；没有转写时提示改发文字
- 语音输出：模型在用户明确要求语音时调用 TTS；只发送 MP3 文件；支持按用户实时切换音色；不是微信原生语音气泡
- 图片：支持 CDN 下载解密并交给视觉模型理解
- 视频：支持 60 秒、20 MiB 以内的短视频固定抽取 10 帧，并用腾讯云 ASR 转写人声后联合理解
- 文件输入：一次只接收 1 个，最大 20 MiB；临时等待下一条指令为 5 分钟，真正处理后登记为可追踪的 `doc_*` 版本资产
- 文件输出：支持 DOCX、XLSX、PDF、TXT；内容由模型工具生成后交给 Java 渲染，二进制不进入聊天历史
- 图片资产：上传或生成图片都会登记 `img_*`；模型可查询当前/最近图片、视觉识别已保存图片、创建新版本或恢复历史版本
- 幂等：当前为内存窗口；生产环境建议改成 Redis
- Java SDK：社区实现，生产使用前需要继续审计并跟踪腾讯官方插件协议变化
