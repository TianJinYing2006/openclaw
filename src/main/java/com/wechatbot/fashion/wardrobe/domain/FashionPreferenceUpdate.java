package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;

public record FashionPreferenceUpdate(
        String dimensionCode,
        String valueCode,
        String polarity,
        BigDecimal weight,
        BigDecimal confidence,
        String source,
        String evidence
) { }
