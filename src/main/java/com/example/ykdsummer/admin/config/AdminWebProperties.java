package com.example.ykdsummer.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local-only settings for the administrator website and encrypted iLink sessions. */
@ConfigurationProperties(prefix = "app.admin")
public class AdminWebProperties {
    private boolean enabled;
    private int port = 8081;
    private String bindAddress = "127.0.0.1";
    private String username = "admin";
    private String password;
    private String sessionEncryptionKey;
    private int maxInstances = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port < 1 || port > 65535 ? 8081 : port; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress.strip();
    }
    public String getUsername() { return username; }
    public void setUsername(String username) {
        this.username = username == null || username.isBlank() ? "admin" : username.strip();
    }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSessionEncryptionKey() { return sessionEncryptionKey; }
    public void setSessionEncryptionKey(String sessionEncryptionKey) { this.sessionEncryptionKey = sessionEncryptionKey; }
    public int getMaxInstances() { return maxInstances; }
    public void setMaxInstances(int maxInstances) { this.maxInstances = Math.max(1, Math.min(20, maxInstances)); }
}
