package com.wechatbot.fashion.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** TJY 文件生成链的输出安全限制。 */
@ConfigurationProperties(prefix = "app.file")
public class FileProcessingProperties {

    private int maxOutputBytes = 20 * 1024 * 1024;

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = Math.max(1024, maxOutputBytes);
    }
}
