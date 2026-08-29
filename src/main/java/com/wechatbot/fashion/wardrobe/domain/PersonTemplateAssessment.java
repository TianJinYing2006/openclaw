package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;

/** Result of checking whether a submitted person photo is suitable for future virtual try-on. */
public record PersonTemplateAssessment(
        FashionPersonTemplateStatus status,
        String suitabilitySummary,
        String retakeGuidance,
        BigDecimal confidence
) {
    public boolean ready() { return status == FashionPersonTemplateStatus.READY; }
}
