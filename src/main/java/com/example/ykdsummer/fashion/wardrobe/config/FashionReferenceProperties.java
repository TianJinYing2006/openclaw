package com.example.ykdsummer.fashion.wardrobe.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Public outfit-reference import and query settings. */
@ConfigurationProperties(prefix = "app.fashion.reference")
public class FashionReferenceProperties {
    private boolean enabled;
    private boolean importEnabled;
    private String annotationFile = "";
    private String imageDirectory = "";
    private int importLimit = 3;
    private boolean publishImported;
    private String includedCategories = "TOP,BOTTOM,OUTERWEAR";
    private String cutoutJobFile = "";
    private String cutoutManifestFile = "";
    private String cutoutImageDirectory = "";

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
    public String getIncludedCategories() { return includedCategories; }
    public void setIncludedCategories(String value) { includedCategories = safe(value); }
    public String getCutoutJobFile() { return cutoutJobFile; }
    public void setCutoutJobFile(String value) { cutoutJobFile = safe(value); }
    public String getCutoutManifestFile() { return cutoutManifestFile; }
    public void setCutoutManifestFile(String value) { cutoutManifestFile = safe(value); }
    public String getCutoutImageDirectory() { return cutoutImageDirectory; }
    public void setCutoutImageDirectory(String value) { cutoutImageDirectory = safe(value); }

    public FashionReferenceImportOptions importOptions() {
        return new FashionReferenceImportOptions(
                FashionReferenceImportOptions.parseCategories(includedCategories),
                path(cutoutJobFile), path(cutoutManifestFile), path(cutoutImageDirectory));
    }

    private static String safe(String value) { return value == null ? "" : value.replace('\0', ' ').strip(); }
    private static Path path(String value) { return value == null || value.isBlank() ? null : Path.of(value); }
}
