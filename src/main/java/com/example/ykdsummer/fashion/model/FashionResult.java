package com.example.ykdsummer.fashion.model;

import java.util.List;

public record FashionResult(
        String scene,
        String finalOutfit,
        String reason,
        List<String> practicalTips,
        List<String> alternatives,
        List<String> warnings,
        String traceSummary
) {
}
