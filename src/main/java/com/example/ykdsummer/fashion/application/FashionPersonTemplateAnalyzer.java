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
            仅根据这张提交的图片判断它是否适合作为虚拟试衣的人物模板。只返回 JSON，不要输出其他内容：
            {"status":"READY|RETAKE_REQUIRED","summary":"...","retakeGuidance":"...","confidence":0.0}，
            其中 summary 与 retakeGuidance 用中文。
            READY 要求：画面中只有一个人且清晰可见、从头到脚完整入镜、光线充足、躯干和腿部大体可见。
            普通站立照可以接受：放松姿势、一只手插兜、手臂自然贴近身体、轻微三分之四侧面，都不算拒绝理由。
            只有以下情况才返回 RETAKE_REQUIRED：画面有多个人、头部或脚部缺失、严重模糊或过暗、只拍到背面、
            或身体被大面积遮挡导致无法依据身体轮廓摆放服装。
            不要推断或提及身份、年龄、性别、民族、体重、身材尺寸、外貌吸引力、健康状况或性格，只描述构图与姿势是否适合。
            不适合时，retakeGuidance 返回一句简短的中文重拍指引。
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
