package com.example.ykdsummer.fashion.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Separate provider and vector-store settings for Fashion semantic retrieval. */
@Component
@ConfigurationProperties(prefix = "app.fashion.semantic")
public class FashionSemanticProperties {
    private boolean enabled;
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String embeddingApiKey = "";
    private String embeddingModel = "text-embedding-v4";
    private int embeddingDimensions = 1024;
    private String qdrantHost = "127.0.0.1";
    private int qdrantPort = 6334;
    private String qdrantApiKey = "";
    private boolean qdrantTls;
    private String collectionName = "ykd_fashion_wardrobe_v1";
    private double similarityThreshold = 0.25d;
    private int candidateMultiplier = 5;
    private Duration dispatchInterval = Duration.ofSeconds(5);
    private Duration processingLease = Duration.ofMinutes(2);
    private int batchSize = 8;
    private int workerThreads = 2;
    private int queueCapacity = 50;
    private int maxAttempts = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String value) { embeddingBaseUrl = text(value, embeddingBaseUrl); }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String value) { embeddingApiKey = text(value, ""); }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String value) { embeddingModel = text(value, embeddingModel); }
    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(int value) { if (value > 0) embeddingDimensions = value; }
    public String getQdrantHost() { return qdrantHost; }
    public void setQdrantHost(String value) { qdrantHost = text(value, qdrantHost); }
    public int getQdrantPort() { return qdrantPort; }
    public void setQdrantPort(int value) { if (value > 0) qdrantPort = value; }
    public String getQdrantApiKey() { return qdrantApiKey; }
    public void setQdrantApiKey(String value) { qdrantApiKey = text(value, ""); }
    public boolean isQdrantTls() { return qdrantTls; }
    public void setQdrantTls(boolean value) { qdrantTls = value; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String value) { collectionName = text(value, collectionName); }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double value) {
        if (value >= 0d && value <= 1d) similarityThreshold = value;
    }
    public int getCandidateMultiplier() { return candidateMultiplier; }
    public void setCandidateMultiplier(int value) { if (value > 0) candidateMultiplier = value; }
    public Duration getDispatchInterval() { return dispatchInterval; }
    public void setDispatchInterval(Duration value) { dispatchInterval = duration(value, dispatchInterval); }
    public Duration getProcessingLease() { return processingLease; }
    public void setProcessingLease(Duration value) { processingLease = duration(value, processingLease); }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { if (value > 0) batchSize = value; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int value) { if (value > 0) workerThreads = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { if (value > 0) queueCapacity = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { if (value > 0) maxAttempts = value; }

    public boolean hasEmbeddingCredentials() {
        return !embeddingApiKey.isBlank() && !"not-configured".equalsIgnoreCase(embeddingApiKey);
    }

    private static String text(String value, String fallback) {
        String cleaned = value == null ? "" : value.replace('\u0000', ' ').strip();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static Duration duration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
