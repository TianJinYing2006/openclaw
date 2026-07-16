package com.example.ykdsummer.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * iLink 功能的集中配置对象。
 *
 * <p>Spring Boot 启动时会把 {@code application.properties} 中以 {@code ilink.} 开头的配置，
 * 按字段名自动绑定到这个类。例如 {@code ilink.enabled} 会调用
 * {@link #setEnabled(boolean)}。业务代码不要自己读取环境变量，只需要注入这个对象。</p>
 *
 * <p>配置值还可以由环境变量或启动参数覆盖，常用的启动参数是
 * {@code --ilink.enabled=true}。这个类只保存配置，不负责建立网络连接。</p>
 */
@Component
@ConfigurationProperties(prefix = "ilink")
public class ILinkProperties {

    /** 是否启用 iLink。false 时 Spring 程序照常启动，但不会连接微信。 */
    private boolean enabled;

    /** SDK 请求的 iLink 服务地址；一般保持默认值，不要随意修改。 */
    private String baseUrl = "https://ilinkai.weixin.qq.com";

    /** SDK 发给服务端的通道版本标识，不是本项目自身的版本号。 */
    private String channelVersion = "1.0.0";

    /** 收到文字消息后发送的固定回复内容。 */
    private String fixedReply = "你好，我已经收到你的文本消息。";

    /** 登录会话和消息游标的本地保存位置；文件含敏感凭据，不能提交到 Git。 */
    private Path sessionFile = Path.of(".ilink", "session.properties");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChannelVersion() {
        return channelVersion;
    }

    public void setChannelVersion(String channelVersion) {
        this.channelVersion = channelVersion;
    }

    public String getFixedReply() {
        return fixedReply;
    }

    public void setFixedReply(String fixedReply) {
        this.fixedReply = fixedReply;
    }

    public Path getSessionFile() {
        return sessionFile;
    }

    public void setSessionFile(Path sessionFile) {
        this.sessionFile = sessionFile;
    }
}
