package com.wechatbot.fashion.ai.service;

import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.ai.model.AiFile;
import com.wechatbot.fashion.ai.model.AiFileMediaTypes;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对当前 OpenAI 兼容中转逐一验证文件输入。默认测试不会联网；只有显式设置
 * AI_FILE_LIVE_TEST=true 且提供 AI_API_KEY 时才执行。
 */
class LiveFileInputProbeTest {

    @Test
    void readsUniqueMarkersFromAllSupportedFileTypes() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("AI_FILE_LIVE_TEST")));
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey));

        String baseUrl = environmentOrDefault("AI_BASE_URL", "https://moosecloud.cc/v1");
        String model = environmentOrDefault("AI_MODEL", "gpt-5.6-sol");
        String reasoningEffort = environmentOrDefault("AI_REASONING_EFFORT", "high");

        AiProperties properties = new AiProperties();
        properties.setModel(model);
        properties.setReasoningEffort(reasoningEffort);
        Map<String, Sample> samples = samples();
        List<String> failures = new ArrayList<>();

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(3))
                .maxRetries(0)
                .build();
        try {
            OpenAiResponsesGateway gateway = new OpenAiResponsesGateway(client, properties);
            for (Map.Entry<String, Sample> entry : samples.entrySet()) {
                long startedAt = System.nanoTime();
                Sample sample = entry.getValue();
                try {
                    AiFile file = new AiFile(
                            sample.fileName(),
                            AiFileMediaTypes.forFileName(sample.fileName()).orElseThrow(),
                            sample.bytes()
                    );
                    String prompt = "请读取这个文件，只原样输出其中的唯一标记，不要解释，不要添加其他文字。";
                    String answer = gateway.generate(List.of(), prompt, List.of(), List.of(file)).text();
                    assertThat(answer).contains(sample.marker());
                    long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                    System.out.printf("FILE_PROBE type=%s success=true durationMs=%d%n", entry.getKey(), durationMs);
                } catch (RuntimeException | AssertionError exception) {
                    failures.add(entry.getKey() + ": " + exception.getClass().getSimpleName()
                            + (exception.getMessage() == null ? "" : " - " + exception.getMessage()));
                    System.out.printf("FILE_PROBE type=%s success=false%n", entry.getKey());
                }
            }
        } finally {
            client.close();
        }

        assertThat(failures).as("file input probe failures").isEmpty();
    }

    private static Map<String, Sample> samples() throws Exception {
        Map<String, Sample> samples = new LinkedHashMap<>();
        samples.put("TXT", text("marker.txt", "YKD_FILE_TXT_20260721_A1B2"));
        samples.put("Markdown", text("marker.md", "# YKD_FILE_MD_20260721_C3D4"));
        samples.put("JSON", text("marker.json", "{\"marker\":\"YKD_FILE_JSON_20260721_E5F6\"}"));
        samples.put("CSV", text("marker.csv", "name,marker\ntest,YKD_FILE_CSV_20260721_G7H8"));
        samples.put("HTML", text("marker.html", "<html><body>YKD_FILE_HTML_20260721_J9K0</body></html>"));
        samples.put("XML", text("marker.xml", "<?xml version=\"1.0\"?><root>YKD_FILE_XML_20260721_L1M2</root>"));
        samples.put("Java", text("Marker.java", "class Marker { String value = \"YKD_FILE_JAVA_20260721_N3P4\"; }"));
        samples.put("PDF", binary("marker.pdf", "YKD_FILE_PDF_20260721_Q5R6", pdf("YKD_FILE_PDF_20260721_Q5R6")));
        samples.put("DOCX", binary("marker.docx", "YKD_FILE_DOCX_20260721_S7T8", docx("YKD_FILE_DOCX_20260721_S7T8")));
        samples.put("XLSX", binary("marker.xlsx", "YKD_FILE_XLSX_20260721_U9V0", xlsx("YKD_FILE_XLSX_20260721_U9V0")));
        samples.put("PPTX", binary("marker.pptx", "YKD_FILE_PPTX_20260721_W1X2", pptx("YKD_FILE_PPTX_20260721_W1X2")));
        return samples;
    }

    private static Sample text(String fileName, String content) {
        String marker = content.replaceAll("(?s).*?(YKD_FILE_[A-Z]+_20260721_[A-Z0-9]+).*", "$1");
        return binary(fileName, marker, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Sample binary(String fileName, String marker, byte[] bytes) {
        return new Sample(fileName, marker, bytes);
    }

    private static byte[] pdf(String marker) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(marker);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx(String marker) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(marker);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx(String marker) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Marker").createRow(0).createCell(0).setCellValue(marker);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx(String marker) throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = presentation.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setText(marker);
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    private record Sample(String fileName, String marker, byte[] bytes) {
    }
}
