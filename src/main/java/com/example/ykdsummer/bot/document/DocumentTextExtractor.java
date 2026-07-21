package com.example.ykdsummer.bot.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * 在 Java 本地把当前文档转换成适合问答的文字，避免中转每一问都重新解析 Base64 二进制附件。
 * 修改文件仍使用原始字节；本类只服务于只读分析。
 */
@Component
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);
    private static final int MAX_EXTRACTED_CHARS = 160_000;

    public Optional<String> extract(String extension, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        try {
            String text = switch (normalized) {
                case "txt", "md", "json", "csv", "html", "htm", "xml", "java" ->
                        new String(bytes, StandardCharsets.UTF_8);
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                case "xlsx" -> extractXlsx(bytes);
                case "pptx" -> extractPptx(bytes);
                default -> "";
            };
            String cleaned = clean(text);
            return cleaned.isBlank() ? Optional.empty() : Optional.of(cleaned);
        } catch (Exception exception) {
            log.warn("Could not extract local document text, extension={}, type={}",
                    normalized, exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String extractPdf(byte[] bytes) throws Exception {
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                appendLine(text, paragraph.getText());
            }
            for (XWPFTable table : document.getTables()) {
                appendTable(text, table);
            }
        }
        return text.toString();
    }

    private static void appendTable(StringBuilder text, XWPFTable table) {
        for (var row : table.getRows()) {
            boolean first = true;
            for (XWPFTableCell cell : row.getTableCells()) {
                if (!first) {
                    text.append("\t");
                }
                text.append(cell.getText());
                first = false;
                for (XWPFTable nested : cell.getTables()) {
                    appendTable(text, nested);
                }
            }
            text.append('\n');
        }
    }

    private static String extractXlsx(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (var sheet : workbook) {
                appendLine(text, "[工作表：" + sheet.getSheetName() + "]");
                for (var row : sheet) {
                    text.append(row.getRowNum() + 1).append(": ");
                    for (var cell : row) {
                        text.append(cell.getAddress().formatAsString()).append('=');
                        text.append(cell.getCellType() == CellType.FORMULA
                                ? "=" + cell.getCellFormula()
                                : formatter.formatCellValue(cell));
                        text.append("\t");
                    }
                    text.append('\n');
                    ensureLimit(text);
                }
            }
        }
        return text.toString();
    }

    private static String extractPptx(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int slideNumber = 0;
            for (var slide : presentation.getSlides()) {
                appendLine(text, "[幻灯片 " + (++slideNumber) + "]");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        appendLine(text, textShape.getText());
                    }
                }
                ensureLimit(text);
            }
        }
        return text.toString();
    }

    private static void appendLine(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            target.append(value.strip()).append('\n');
            ensureLimit(target);
        }
    }

    private static void ensureLimit(StringBuilder text) {
        if (text.length() > MAX_EXTRACTED_CHARS) {
            text.setLength(MAX_EXTRACTED_CHARS);
        }
    }

    private static String clean(String value) {
        String cleaned = value == null ? "" : value.replace("\u0000", "").strip();
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.length() <= MAX_EXTRACTED_CHARS
                ? cleaned
                : cleaned.substring(0, MAX_EXTRACTED_CHARS);
    }
}
