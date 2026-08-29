package com.wechatbot.fashion.wardrobe.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Candidate limits, deterministic ranking weights, and background rendering deadlines. */
@ConfigurationProperties(prefix = "app.fashion.outfit-recommendation")
public class OutfitRecommendationProperties {
    private boolean enabled = true;
    private int publicCandidateLimit = 20;
    private int wardrobeCandidatesPerRole = 8;
    private double minimumCompanionSimilarity = 0.30d;
    private double evidenceWeight = 0.30d;
    private double occasionWeight = 0.15d;
    private double seasonWeatherWeight = 0.15d;
    private double colorWeight = 0.15d;
    private double styleWeight = 0.10d;
    private double fitFormalityWeight = 0.05d;
    private double preferenceWeight = 0.05d;
    private double noveltyWeight = 0.05d;
    private Duration providerTimeout = Duration.ofSeconds(150);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPublicCandidateLimit() { return publicCandidateLimit; }
    public void setPublicCandidateLimit(int value) { publicCandidateLimit = bounded(value, 1, 50); }
    public int getWardrobeCandidatesPerRole() { return wardrobeCandidatesPerRole; }
    public void setWardrobeCandidatesPerRole(int value) { wardrobeCandidatesPerRole = bounded(value, 1, 20); }
    public double getMinimumCompanionSimilarity() { return minimumCompanionSimilarity; }
    public void setMinimumCompanionSimilarity(double value) { minimumCompanionSimilarity = unit(value, minimumCompanionSimilarity); }
    public double getEvidenceWeight() { return evidenceWeight; }
    public void setEvidenceWeight(double value) { evidenceWeight = unit(value, evidenceWeight); }
    public double getOccasionWeight() { return occasionWeight; }
    public void setOccasionWeight(double value) { occasionWeight = unit(value, occasionWeight); }
    public double getSeasonWeatherWeight() { return seasonWeatherWeight; }
    public void setSeasonWeatherWeight(double value) { seasonWeatherWeight = unit(value, seasonWeatherWeight); }
    public double getColorWeight() { return colorWeight; }
    public void setColorWeight(double value) { colorWeight = unit(value, colorWeight); }
    public double getStyleWeight() { return styleWeight; }
    public void setStyleWeight(double value) { styleWeight = unit(value, styleWeight); }
    public double getFitFormalityWeight() { return fitFormalityWeight; }
    public void setFitFormalityWeight(double value) { fitFormalityWeight = unit(value, fitFormalityWeight); }
    public double getPreferenceWeight() { return preferenceWeight; }
    public void setPreferenceWeight(double value) { preferenceWeight = unit(value, preferenceWeight); }
    public double getNoveltyWeight() { return noveltyWeight; }
    public void setNoveltyWeight(double value) { noveltyWeight = unit(value, noveltyWeight); }
    public Duration getProviderTimeout() { return providerTimeout; }
    public void setProviderTimeout(Duration value) {
        if (value != null && !value.isNegative() && !value.isZero()) providerTimeout = value;
    }

    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
    private static double unit(double value, double fallback) {
        return Double.isFinite(value) && value >= 0d && value <= 1d ? value : fallback;
    }
}
