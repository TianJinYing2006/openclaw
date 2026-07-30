package com.example.ykdsummer.fashion.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable options for one explicit public-reference import run. */
public record FashionReferenceImportOptions(
        Set<String> includedCategories,
        Path cutoutJobFile,
        Path cutoutManifestFile,
        Path cutoutImageDirectory
) {
    public static final Set<String> DEFAULT_CATEGORIES = Set.of("TOP", "BOTTOM", "OUTERWEAR");

    public FashionReferenceImportOptions {
        includedCategories = normalizeCategories(includedCategories);
        cutoutJobFile = normalize(cutoutJobFile);
        cutoutManifestFile = normalize(cutoutManifestFile);
        cutoutImageDirectory = normalize(cutoutImageDirectory);
    }

    public static FashionReferenceImportOptions defaults() {
        return new FashionReferenceImportOptions(DEFAULT_CATEGORIES, null, null, null);
    }

    public boolean includes(String categoryCode) {
        return includedCategories.contains(normalizeCode(categoryCode));
    }

    private static Set<String> normalizeCategories(Set<String> values) {
        if (values == null || values.isEmpty()) return DEFAULT_CATEGORIES;
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            String code = normalizeCode(value);
            if (!code.isBlank()) result.add(code);
        });
        return result.isEmpty() ? DEFAULT_CATEGORIES : Set.copyOf(result);
    }

    public static Set<String> parseCategories(String value) {
        if (value == null || value.isBlank()) return DEFAULT_CATEGORIES;
        return normalizeCategories(new LinkedHashSet<>(Arrays.asList(value.split(","))));
    }

    private static Path normalize(Path value) {
        return value == null ? null : value.toAbsolutePath().normalize();
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }
}
