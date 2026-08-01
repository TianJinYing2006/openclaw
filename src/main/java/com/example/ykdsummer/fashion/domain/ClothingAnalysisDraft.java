package com.example.ykdsummer.fashion.domain;

import java.math.BigDecimal;

public record ClothingAnalysisDraft(
        Long wardrobeItemId,
        String categoryCode,
        String attributesJson,
        BigDecimal confidence,
        String analysisStatus,
        String provider,
        String model,
        String promptVersion,
        String failureSummary
) { }
