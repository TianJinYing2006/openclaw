package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.ImageInspectionService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplateStatus;
import com.example.ykdsummer.fashion.domain.PersonTemplateAssessment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Conservative quality gate for a private photo that may later be used for virtual try-on. */
@Service
public class FashionPersonTemplateAnalyzer {
    private static final String PROMPT = """
            Inspect this submitted image only to decide whether it is suitable as a virtual try-on person template.
            Return JSON only: {"status":"READY|RETAKE_REQUIRED","summary":"...","retakeGuidance":"...","confidence":0.0}.
            READY requires one clearly visible person, the full body from head to feet, adequate lighting, and a generally visible
            torso and legs. Normal standing photos are acceptable: a relaxed pose, one hand in a pocket, arms naturally close to the
            body, and a slight three-quarter angle are not reasons to reject the photo. Use RETAKE_REQUIRED only for multiple people,
            missing head or feet, severe blur or darkness, a back-only view, or major covering that prevents the body outline from
            being used for clothing placement. Do not infer or mention identity, age, gender, ethnicity, weight, body measurements,
            attractiveness, health, or personality. Only describe framing and pose suitability. When unsuitable, return one concise
            rephoto instruction.
            """;
    private final ImageInspectionService inspection;
    private final ObjectMapper objectMapper;

    public FashionPersonTemplateAnalyzer(ImageInspectionService inspection, ObjectMapper objectMapper) {
        this.inspection = inspection;
        this.objectMapper = objectMapper;
    }

    public PersonTemplateAssessment analyze(StoredImage image) {
        if (image == null) throw new IllegalArgumentException("Template image is required");
        return parse(inspection.inspect(image, PROMPT));
    }

    PersonTemplateAssessment parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            FashionPersonTemplateStatus status = status(root.path("status").asText());
            String summary = text(root.path("summary").asText(), 512);
            String guidance = text(root.path("retakeGuidance").asText(), 512);
            BigDecimal confidence = confidence(root.path("confidence"));
            if (status != FashionPersonTemplateStatus.READY || confidence.compareTo(new BigDecimal("0.65")) < 0) {
                status = FashionPersonTemplateStatus.RETAKE_REQUIRED;
                if (guidance.isBlank()) guidance = "请拍摄一张单人、正面或近正面、从头到脚完整入镜的清晰全身照。";
            }
            return new PersonTemplateAssessment(status, summary, guidance, confidence);
        } catch (Exception exception) {
            return new PersonTemplateAssessment(FashionPersonTemplateStatus.RETAKE_REQUIRED, "", 
                    "图片模板识别失败，请重新发送单人、正面、完整全身入镜的清晰照片。", BigDecimal.ZERO);
        }
    }

    private static FashionPersonTemplateStatus status(String value) {
        try {
            return FashionPersonTemplateStatus.valueOf(text(value, 32).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return FashionPersonTemplateStatus.RETAKE_REQUIRED;
        }
    }

    private static BigDecimal confidence(JsonNode value) {
        if (value == null || !value.isNumber()) return BigDecimal.ZERO;
        BigDecimal score = value.decimalValue();
        return score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ZERO : score;
    }

    private static String extractJson(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) value = value.substring(firstNewline + 1, lastFence).strip();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end >= start ? value.substring(start, end + 1) : "{}";
    }

    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
