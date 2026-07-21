package com.example.ykdsummer.bot.document;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.service.OpenAiResponsesGateway;
import com.example.ykdsummer.bot.config.DocumentEditProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认跳过；显式开启后验证“PDF 只读分析不增版本”和“明确修改生成 v2”的真实链路。 */
class LiveDocumentEditProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void modifiesPdfAndReturnsAReadableNewVersion() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("AI_DOCUMENT_EDIT_LIVE_TEST")));
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey));

        String originalMarker = "YKD_DOCUMENT_ORIGINAL_20260721";
        String modifiedMarker = "YKD_DOCUMENT_MODIFIED_20260721";
        DocumentRenderer renderer = new DocumentRenderer();
        byte[] originalPdf = renderer.render("pdf", "测试文件\n" + originalMarker);
        DocumentEditProperties documentProperties = new DocumentEditProperties();
        documentProperties.setStorageDirectory(tempDir);
        DocumentSessionService sessions = new DocumentSessionService(documentProperties);
        sessions.open("live-user", new AiFile("probe.pdf", "application/pdf", originalPdf));

        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel(environmentOrDefault("AI_MODEL", "gpt-5.6-sol"));
        aiProperties.setReasoningEffort(environmentOrDefault("AI_REASONING_EFFORT", "high"));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(environmentOrDefault("AI_BASE_URL", "https://moosecloud.cc/v1")))
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(3))
                .maxRetries(0)
                .build();
        try {
            OpenAiResponsesGateway gateway = new OpenAiResponsesGateway(client, aiProperties);
            DocumentAnalysisService analysisService = new DocumentAnalysisService(sessions, gateway);
            String analysis = analysisService.analyze("live-user", "文件中的唯一标记是什么？请原样读出");
            assertThat(analysis).contains(originalMarker);
            assertThat(sessions.current("live-user")).get()
                    .extracting(DocumentSessionService.DocumentSnapshot::currentVersion).isEqualTo(1);

            DocumentEditService service = new DocumentEditService(sessions, gateway, renderer);
            DocumentEditService.EditResult result = service.edit(
                    "live-user",
                    "把标记 " + originalMarker + " 替换成 " + modifiedMarker + "，其他内容保持不变"
            );
            try (var document = Loader.loadPDF(result.bytes())) {
                String text = new PDFTextStripper().getText(document);
                assertThat(text).contains(modifiedMarker).doesNotContain(originalMarker);
            }
            assertThat(result.snapshot().currentVersion()).isEqualTo(2);
            System.out.println("DOCUMENT_MODE_PROBE type=PDF analysis=true edit=true version=2");
        } finally {
            client.close();
        }
    }

    @Test
    void modifiesXlsxWithoutFlatteningWorkbook() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("AI_DOCUMENT_EDIT_LIVE_TEST")));
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey));

        byte[] original;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var data = workbook.createSheet("数据");
            data.createRow(0).createCell(0).setCellValue("旧标题");
            data.createRow(1).createCell(0).setCellFormula("1+1");
            workbook.createSheet("保留页").createRow(0).createCell(0).setCellValue("不能删除");
            workbook.write(output);
            original = output.toByteArray();
        }
        DocumentEditProperties documentProperties = new DocumentEditProperties();
        documentProperties.setStorageDirectory(tempDir.resolve("xlsx"));
        DocumentSessionService sessions = new DocumentSessionService(documentProperties);
        sessions.open("xlsx-user", new AiFile("probe.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", original));

        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel(environmentOrDefault("AI_MODEL", "gpt-5.6-sol"));
        aiProperties.setReasoningEffort(environmentOrDefault("AI_REASONING_EFFORT", "low"));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(environmentOrDefault("AI_BASE_URL", "https://moosecloud.cc/v1")))
                .apiKey(apiKey).timeout(Duration.ofMinutes(3)).maxRetries(0).build();
        try {
            DocumentEditService service = new DocumentEditService(
                    sessions, new OpenAiResponsesGateway(client, aiProperties), new DocumentRenderer());
            DocumentEditService.EditResult result = service.edit(
                    "xlsx-user", "把“数据”工作表的 A1 从“旧标题”改成“新标题”，其他内容保持不变");
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.bytes()))) {
                assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
                assertThat(workbook.getSheet("数据").getRow(0).getCell(0).getStringCellValue()).isEqualTo("新标题");
                assertThat(workbook.getSheet("数据").getRow(1).getCell(0).getCellFormula()).isEqualTo("1+1");
                assertThat(workbook.getSheet("保留页").getRow(0).getCell(0).getStringCellValue()).isEqualTo("不能删除");
            }
            System.out.println("DOCUMENT_MODE_PROBE type=XLSX edit=true structurePreserved=true version=2");
        } finally {
            client.close();
        }
    }

    @Test
    void answersDocxThroughLocallyExtractedText() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("AI_DOCUMENT_EDIT_LIVE_TEST")));
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey));

        String marker = "YKD_DOCX_LOCAL_TEXT_20260721";
        byte[] docx = new DocumentRenderer().render("docx", "公司简介\n唯一标记：" + marker);
        DocumentEditProperties documentProperties = new DocumentEditProperties();
        documentProperties.setStorageDirectory(tempDir.resolve("docx-analysis"));
        DocumentSessionService sessions = new DocumentSessionService(documentProperties);
        sessions.open("docx-user", new AiFile("company.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx));

        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel(environmentOrDefault("AI_MODEL", "gpt-5.6-sol"));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(environmentOrDefault("AI_BASE_URL", "https://moosecloud.cc/v1")))
                .apiKey(apiKey).timeout(Duration.ofMinutes(2)).maxRetries(0).build();
        try {
            DocumentAnalysisService service = new DocumentAnalysisService(
                    sessions, new OpenAiResponsesGateway(client, aiProperties));
            String answer = service.analyze("docx-user", "请原样回答唯一标记是什么");
            assertThat(answer).contains(marker);
            assertThat(sessions.current("docx-user")).get()
                    .extracting(DocumentSessionService.DocumentSnapshot::currentVersion).isEqualTo(1);
            System.out.println("DOCUMENT_MODE_PROBE type=DOCX localText=true analysis=true version=1");
        } finally {
            client.close();
        }
    }

    @Test
    void semanticallyRewritesDocxInsteadOfOnlyAppendingASummary() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("AI_DOCUMENT_EDIT_LIVE_TEST")));
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey));

        StringBuilder source = new StringBuilder("# 销售工作计划\n");
        for (int index = 1; index <= 40; index++) {
            source.append("原计划段落 ").append(index).append("：YKD_OLD_PLAN_").append(index).append("\n");
        }
        DocumentRenderer renderer = new DocumentRenderer();
        byte[] original = renderer.render("docx", source.toString());
        DocumentEditProperties documentProperties = new DocumentEditProperties();
        documentProperties.setStorageDirectory(tempDir.resolve("docx-rewrite"));
        DocumentSessionService sessions = new DocumentSessionService(documentProperties);
        sessions.open("rewrite-user", new AiFile("销售工作计划.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", original));

        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel(environmentOrDefault("AI_MODEL", "gpt-5.6-sol"));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(environmentOrDefault("AI_BASE_URL", "https://moosecloud.cc/v1")))
                .apiKey(apiKey).timeout(Duration.ofMinutes(2)).maxRetries(0).build();
        try {
            DocumentEditService service = new DocumentEditService(
                    sessions, new OpenAiResponsesGateway(client, aiProperties), renderer);
            DocumentEditService.EditResult result = service.edit("rewrite-user", "生成一个你的理解word给我");
            assertThat(result.warning()).contains("全文重构");
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
                String text = document.getParagraphs().stream()
                        .map(paragraph -> paragraph.getText()).collect(java.util.stream.Collectors.joining("\n"));
                assertThat(text).contains("销售").doesNotContain("YKD_OLD_PLAN_40");
            }
            System.out.println("DOCUMENT_MODE_PROBE type=DOCX semanticRewrite=true appendOnly=false version=2");
        } finally {
            client.close();
        }
    }

    @Test
    void generatesIndependentDocxWithoutChangingCurrentVersion() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("AI_DOCUMENT_EDIT_LIVE_TEST")));
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey));

        String sourceMarker = "YKD_GENERATION_SOURCE_20260721";
        String requiredMarker = "YKD_GENERATED_ADVICE_20260721";
        DocumentRenderer renderer = new DocumentRenderer();
        byte[] source = renderer.render("docx", "# 产品说明\n企业协作平台\n唯一参考标记：" + sourceMarker);
        DocumentEditProperties documentProperties = new DocumentEditProperties();
        documentProperties.setStorageDirectory(tempDir.resolve("docx-generation"));
        DocumentSessionService sessions = new DocumentSessionService(documentProperties);
        sessions.open("generation-user", new AiFile("产品说明.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", source));

        AiProperties aiProperties = new AiProperties();
        aiProperties.setModel(environmentOrDefault("AI_MODEL", "gpt-5.6-sol"));
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(environmentOrDefault("AI_BASE_URL", "https://moosecloud.cc/v1")))
                .apiKey(apiKey).timeout(Duration.ofMinutes(2)).maxRetries(0).build();
        try {
            DocumentGenerationService service = new DocumentGenerationService(
                    sessions, new OpenAiResponsesGateway(client, aiProperties),
                    new DocumentTextExtractor(), documentProperties, renderer);
            DocumentGenerationService.GenerationResult result = service.generate(
                    "generation-user",
                    "生成：基于当前文件另外生成一份产品改进建议 Word，正文必须原样包含标记 " + requiredMarker);

            assertThat(result.fileName()).endsWith(".docx");
            assertThat(result.sourceSnapshot().currentVersion()).isEqualTo(1);
            assertThat(sessions.current("generation-user")).get()
                    .extracting(DocumentSessionService.DocumentSnapshot::currentVersion).isEqualTo(1);
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
                String text = document.getParagraphs().stream()
                        .map(paragraph -> paragraph.getText()).collect(java.util.stream.Collectors.joining("\n"));
                assertThat(text).contains("建议", requiredMarker);
            }
            System.out.println("DOCUMENT_MODE_PROBE type=DOCX generation=true independent=true version=1");
        } finally {
            client.close();
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
}
