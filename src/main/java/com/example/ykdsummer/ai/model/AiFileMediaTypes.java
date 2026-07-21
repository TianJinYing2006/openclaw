package com.example.ykdsummer.ai.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 显式维护当前中转已验证的扩展名到 MIME 映射。 */
public final class AiFileMediaTypes {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("json", "application/json"),
            Map.entry("csv", "text/csv"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("xml", "text/xml"),
            Map.entry("java", "text/x-java"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    );

    private AiFileMediaTypes() {
    }

    public static Optional<String> forFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(TYPES.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }
}
