package com.example.ykdsummer.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.persistence")
public class PersistenceProperties {
    private boolean enabled;
    private String jdbcUrl;
    private String username;
    private String password;
    private int maximumPoolSize = 10;
    private Duration connectionTimeout = Duration.ofSeconds(5);
    private final Redis redis = new Redis();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
    public Duration getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(Duration connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    public Redis getRedis() { return redis; }

    public static class Redis {
        private boolean enabled;
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password;
        private String keyPrefix = "ykd";
        private Duration messageDedupTtl = Duration.ofHours(24);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public Duration getMessageDedupTtl() { return messageDedupTtl; }
        public void setMessageDedupTtl(Duration messageDedupTtl) {
            this.messageDedupTtl = messageDedupTtl == null || messageDedupTtl.isZero() || messageDedupTtl.isNegative()
                    ? Duration.ofHours(24) : messageDedupTtl;
        }
    }
}
