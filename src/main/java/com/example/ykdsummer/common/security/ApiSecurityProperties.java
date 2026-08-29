package com.example.ykdsummer.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * /api/** 调试接口的访问控制配置。
 *
 * <p>开发期默认只允许本机（loopback）访问，防止局域网内其它机器直接调用
 * 主动发消息等调试接口。需要多机联调时可设为 false 关闭限制。</p>
 */
@ConfigurationProperties("app.api-security")
public class ApiSecurityProperties {

    /** /api/** 是否仅允许本机访问；默认开启。 */
    private boolean localhostOnly = true;

    public boolean isLocalhostOnly() {
        return localhostOnly;
    }

    public void setLocalhostOnly(boolean localhostOnly) {
        this.localhostOnly = localhostOnly;
    }
}
