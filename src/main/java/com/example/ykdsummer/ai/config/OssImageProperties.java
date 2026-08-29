package com.example.ykdsummer.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 图片资产专用的 OSS 连接配置；不与文字模型、iLink 登录或文档存储共用密钥。 */
@ConfigurationProperties(prefix = "oss.image")
public class OssImageProperties {
    private String endpoint = "";
    private String accessKeyId = "";
    private String accessKeySecret = "";
    private String bucketName = "";
    private String prefix = "ilink-bot/images";
    private Duration signedUrlTtl = Duration.ofMinutes(10);
    private boolean migrateLocalAssets;

    public boolean isConfigured() {
        return present(endpoint) && present(accessKeyId) && present(accessKeySecret) && present(bucketName);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && !"not-configured".equalsIgnoreCase(value.trim());
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getAccessKeySecret() { return accessKeySecret; }
    public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix == null ? "ilink-bot/images" : prefix.strip(); }
    public Duration getSignedUrlTtl() { return signedUrlTtl; }
    public void setSignedUrlTtl(Duration signedUrlTtl) {
        this.signedUrlTtl = signedUrlTtl == null || signedUrlTtl.isNegative() || signedUrlTtl.isZero()
                ? Duration.ofMinutes(10) : signedUrlTtl;
    }
    public boolean isMigrateLocalAssets() { return migrateLocalAssets; }
    public void setMigrateLocalAssets(boolean migrateLocalAssets) { this.migrateLocalAssets = migrateLocalAssets; }
}
