package com.example.ykdsummer.fashion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Public outfit-reference import and query settings. */
@Component
@ConfigurationProperties(prefix = "app.fashion.reference")
public class FashionReferenceProperties {
    private boolean enabled;
    private boolean importEnabled;
    private String annotationFile = "";
    private String imageDirectory = "";
    private int importLimit = 3;
    private boolean publishImported;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isImportEnabled() { return importEnabled; }
    public void setImportEnabled(boolean importEnabled) { this.importEnabled = importEnabled; }
    public String getAnnotationFile() { return annotationFile; }
    public void setAnnotationFile(String value) { annotationFile = safe(value); }
    public String getImageDirectory() { return imageDirectory; }
    public void setImageDirectory(String value) { imageDirectory = safe(value); }
    public int getImportLimit() { return importLimit; }
    public void setImportLimit(int value) { importLimit = Math.max(1, Math.min(value, 1000)); }
    public boolean isPublishImported() { return publishImported; }
    public void setPublishImported(boolean value) { publishImported = value; }
    private static String safe(String value) { return value == null ? "" : value.replace('\0', ' ').strip(); }
}
