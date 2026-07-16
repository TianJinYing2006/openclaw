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

## 9. 当前能力边界

- 文本：已接收、已固定回复
- 语音：识别类型并读取腾讯提供的转写字段，暂不自动回复
- 图片：识别类型，暂不下载和解密 CDN 内容
- 文件/视频：识别类型，暂不处理内容
- 幂等：当前为内存窗口；生产环境建议改成 Redis
- Java SDK：社区实现，生产使用前需要继续审计并跟踪腾讯官方插件协议变化

