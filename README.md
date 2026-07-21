# YKD Summer 微信 AI 机器人

这是一个基于 Spring Boot 的微信 iLink 机器人：支持普通对话、图片理解与生成、语音识别与 MP3 回复、短视频分析、文件识别和自然语言文件生成；普通文本已预留 Spring AI Completion / Tool 的扩展通道。

## 从这里开始

1. 看 [项目结构说明](docs/architecture/PROJECT_STRUCTURE.md)，先理解每个包负责什么。
2. 看 [运行指南](docs/getting-started/ILINK_GUIDE.md)，配置本地环境并启动。
3. 看 [代码导读](docs/getting-started/ILINK_CODE_WALKTHROUGH.md)，按启动、收消息、分流、回复的顺序阅读源码。

## 最常用命令

```powershell
mvn test
mvn spring-boot:run
mvn package -DskipTests
```

项目要求 JDK 21 与 Maven 3.9.x。首次真正连接微信前，需要在 IDEA 运行配置或系统环境变量中设置 `ILINK_ENABLED=true` 和相应 AI 密钥；密钥不要写入 `application.properties`。

## 目录速览

```text
src/main/java/com/example/ykdsummer/
├── ai/        大模型协议、上下文、图片生成、Spring AI Tool
├── bot/       微信 iLink 接入、消息路由、音视频和文件处理
├── weather/   独立业务服务示例（供 Tool 调用）
└── YkdSummerApplication.java  Spring Boot 启动入口

src/test/java/             与 main 按包结构对应的测试
src/main/resources/        application.properties（只放默认配置，不放密钥）
docs/                      使用、架构、功能、开发和历史资料
```

## 文档入口

- [文档目录](docs/README.md)
- [完整处理时序](docs/architecture/ILINK_PROJECT_SEQUENCE_GUIDE.md)
- [Git 协作指南](docs/development/GIT_GUIDE.md)

`target/`、`.ilink/`、`.documents/` 和 `.idea/` 都是构建、运行或 IDE 本地数据，不是业务源码，也不应提交到 Git。
