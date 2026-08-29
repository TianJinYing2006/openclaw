package com.wechatbot.fashion.bot.document;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.dromara.pdf.pdfbox.core.base.Document;
import org.dromara.pdf.pdfbox.core.base.Page;
import org.dromara.pdf.pdfbox.core.component.Textarea;
import org.dromara.pdf.pdfbox.handler.PdfHandler;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 生成文本型文件；对 Office 文件还可在原文件字节上执行受限操作，以保留未修改结构。 */
@Component
public class DocumentRenderer {

    private static final int MAX_TEXT_CHARS = 1_000_000;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_OPERATIONS = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    public byte[] render(String extension, String content) {
        String normalizedExtension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        String safeContent = requireContent(content);
        try {
            return switch (normalizedExtension) {
                case "txt", "md", "html", "htm", "java" -> safeContent.getBytes(StandardCharsets.UTF_8);
                case "json" -> renderJson(safeContent);
                case "xml" -> renderXml(safeContent);
                case "csv" -> renderCsv(safeContent);
                case "docx" -> renderDocx(safeContent);
                case "xlsx" -> renderXlsx(safeContent);
                case "pptx" -> renderPptx(safeContent);
                case "pdf" -> renderPdf(safeContent);
                default -> throw new DocumentEditException(
                        "Unsupported document output type: " + normalizedExtension,
                        "当前文件格式暂不支持修改"
                );
            };
        } catch (DocumentEditException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentEditException("Could not render document", "修改内容无法生成有效文件，请换一种说法重试", exception);
        }
    }

    public byte[] applyOperations(String extension, byte[] original, String operationsJson) {
        if (original == null || original.length == 0) {
            throw new DocumentEditException("Original Office document is empty", "当前文件内容为空，请重新上传");
        }
        JsonNode root;
        try {
            root = JSON.readTree(requireContent(operationsJson));
        } catch (IOException exception) {
            throw new DocumentEditException("Office operations are not valid JSON", "模型返回的修改步骤格式不正确，请重新描述要求", exception);
        }
        JsonNode operations = root.path("operations");
        if (!operations.isArray() || operations.isEmpty() || operations.size() > MAX_OPERATIONS) {
            throw new DocumentEditException("Office operations are missing or exceed limit",
                    "这条内容没有形成可执行的修改步骤，文件没有被修改。请发送“修改：把具体原文改成具体新内容”");
        }
        try {
            return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
                case "docx" -> editDocx(original, operations);
                case "xlsx" -> editXlsx(original, operations);
                case "pptx" -> editPptx(original, operations);
                default -> throw new DocumentEditException("Unsupported operation-based type", "当前文件格式不支持结构化修改");
            };
        } catch (DocumentEditException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentEditException("Could not apply Office operations", "修改步骤无法应用到当前文件，请把位置和内容说得更具体", exception);
        }
    }

    private static byte[] editDocx(byte[] original, JsonNode operations) throws IOException {
        for (JsonNode operation : operations) {
            if ("replace_document".equals(requiredText(operation, "type"))) {
                if (operations.size() != 1) {
                    throw new DocumentEditException("replace_document cannot be mixed with other operations",
                            "全文重构不能和局部修改混在一起，请重新发送要求");
                }
                return renderDocx(requiredText(operation, "content"));
            }
        }
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(original));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (JsonNode operation : operations) {
                switch (requiredText(operation, "type")) {
                    case "replace_text" -> {
                        String find = requiredText(operation, "find");
                        String replace = text(operation, "replace");
                        int changed = replaceInDocx(document, find, replace);
                        requireMatch(changed, find);
                    }
                    case "append_paragraph" -> document.createParagraph().createRun()
                            .setText(requiredText(operation, "text"));
                    default -> throw unsupportedOperation(operation);
                }
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    private static int replaceInDocx(XWPFDocument document, String find, String replace) {
        int changed = 0;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            changed += replaceParagraph(paragraph, find, replace);
        }
        for (XWPFTable table : document.getTables()) {
            for (var row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    changed += replaceInCell(cell, find, replace);
                }
            }
        }
        return changed;
    }

    private static int replaceInCell(XWPFTableCell cell, String find, String replace) {
        int changed = 0;
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            changed += replaceParagraph(paragraph, find, replace);
        }
        for (XWPFTable nested : cell.getTables()) {
            for (var row : nested.getRows()) {
                for (XWPFTableCell nestedCell : row.getTableCells()) {
                    changed += replaceInCell(nestedCell, find, replace);
                }
            }
        }
        return changed;
    }

    private static int replaceParagraph(XWPFParagraph paragraph, String find, String replace) {
        String before = paragraph.getText();
        if (before == null || !before.contains(find)) {
            return 0;
        }
        String after = before.replace(find, replace);
        if (paragraph.getRuns().isEmpty()) {
            paragraph.createRun().setText(after);
        } else {
            paragraph.getRuns().getFirst().setText(after, 0);
            for (int index = 1; index < paragraph.getRuns().size(); index++) {
                paragraph.getRuns().get(index).setText("", 0);
            }
        }
        return countOccurrences(before, find);
    }

    private static byte[] editXlsx(byte[] original, JsonNode operations) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(original));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (JsonNode operation : operations) {
                switch (requiredText(operation, "type")) {
                    case "set_cell" -> setCell(workbook, operation);
                    case "replace_text" -> {
                        String find = requiredText(operation, "find");
                        String replace = text(operation, "replace");
                        int changed = 0;
                        for (var sheet : workbook) {
                            for (var row : sheet) {
                                for (Cell cell : row) {
                                    if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains(find)) {
                                        cell.setCellValue(cell.getStringCellValue().replace(find, replace));
                                        changed++;
                                    }
                                }
                            }
                        }
                        requireMatch(changed, find);
                    }
                    default -> throw unsupportedOperation(operation);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void setCell(XSSFWorkbook workbook, JsonNode operation) {
        String sheetName = requiredText(operation, "sheet");
        var sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new DocumentEditException("Spreadsheet sheet not found: " + sheetName, "没有找到工作表：" + sheetName);
        }
        CellReference reference;
        try {
            reference = new CellReference(requiredText(operation, "cell"));
        } catch (IllegalArgumentException exception) {
            throw new DocumentEditException("Invalid spreadsheet cell", "单元格位置无效，请使用 A1 这样的格式", exception);
        }
        var row = sheet.getRow(reference.getRow());
        if (row == null) {
            row = sheet.createRow(reference.getRow());
        }
        Cell cell = row.getCell(reference.getCol());
        if (cell == null) {
            cell = row.createCell(reference.getCol());
        }
        JsonNode value = operation.get("value");
        if (value == null || value.isNull()) {
            cell.setBlank();
        } else if (value.isNumber()) {
            cell.setCellValue(value.doubleValue());
        } else if (value.isBoolean()) {
            cell.setCellValue(value.booleanValue());
        } else {
            cell.setCellValue(value.asText());
        }
    }

    private static byte[] editPptx(byte[] original, JsonNode operations) throws IOException {
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(original));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (JsonNode operation : operations) {
                switch (requiredText(operation, "type")) {
                    case "replace_text" -> {
                        String find = requiredText(operation, "find");
                        String replace = text(operation, "replace");
                        int changed = 0;
                        for (XSLFSlide slide : presentation.getSlides()) {
                            for (XSLFShape shape : slide.getShapes()) {
                                if (shape instanceof XSLFTextShape textShape && textShape.getText().contains(find)) {
                                    textShape.setText(textShape.getText().replace(find, replace));
                                    changed++;
                                }
                            }
                        }
                        requireMatch(changed, find);
                    }
                    case "append_slide" -> appendSlide(presentation,
                            requiredText(operation, "title"), text(operation, "body"));
                    default -> throw unsupportedOperation(operation);
                }
            }
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static void appendSlide(XMLSlideShow presentation, String title, String body) {
        XSLFSlide slide = presentation.createSlide();
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle2D.Double(55, 35, 850, 90));
        titleBox.setText(title);
        XSLFTextBox bodyBox = slide.createTextBox();
        bodyBox.setAnchor(new Rectangle2D.Double(65, 140, 830, 360));
        bodyBox.setText(body);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field).strip();
        if (value.isBlank()) {
            throw new DocumentEditException("Office operation field is blank: " + field, "修改步骤缺少必要字段：" + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static void requireMatch(int changed, String find) {
        if (changed == 0) {
            throw new DocumentEditException("Office operation target was not found: " + find, "没有在当前文件中找到要修改的原文：" + find);
        }
    }

    private static DocumentEditException unsupportedOperation(JsonNode operation) {
        return new DocumentEditException("Unsupported Office operation: " + text(operation, "type"), "模型返回了不支持的修改步骤，请重新描述要求");
    }

    private static int countOccurrences(String text, String find) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(find, index)) >= 0; index += find.length()) {
            count++;
        }
        return count;
    }

    private static byte[] renderJson(String content) throws IOException {
        Object value = JSON.readValue(content, Object.class);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    private static byte[] renderXml(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException exception) throws SAXParseException { throw exception; }
            @Override public void fatalError(SAXParseException exception) throws SAXParseException { throw exception; }
        });
        builder.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] renderCsv(String content) throws IOException {
        try (CSVParser parser = CSVParser.parse(content, CSVFormat.DEFAULT);
             StringWriter writer = new StringWriter();
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            int rows = 0;
            for (CSVRecord record : parser) {
                if (++rows > MAX_ROWS || record.size() > MAX_COLUMNS) {
                    throw new DocumentEditException("CSV exceeds configured limits", "CSV 内容过大，无法生成");
                }
                List<String> values = new ArrayList<>(record.size());
                for (String value : record) {
                    values.add(neutralizeFormula(value));
                }
                printer.printRecord(values);
            }
            printer.flush();
            return writer.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] renderDocx(String content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String line : content.split("\\R", -1)) {
                XWPFParagraph paragraph = document.createParagraph();
                String text = line;
                if (line.startsWith("# ")) {
                    paragraph.setStyle("Title");
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                    text = line.substring(2);
                } else if (line.matches("^#{2,6} .+")) {
                    int level = Math.min(6, line.indexOf(' '));
                    paragraph.setStyle("Heading" + level);
                    text = line.substring(line.indexOf(' ') + 1);
                }
                paragraph.createRun().setText(text);
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] renderXlsx(String content) throws IOException {
        try (CSVParser parser = CSVParser.parse(content, CSVFormat.DEFAULT);
             XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("修改结果");
            int rowIndex = 0;
            for (CSVRecord record : parser) {
                if (rowIndex >= MAX_ROWS) {
                    throw new DocumentEditException("Spreadsheet exceeds row limit", "表格行数超过 10000 行，无法生成");
                }
                var row = sheet.createRow(rowIndex++);
                int columnCount = Math.min(record.size(), MAX_COLUMNS);
                for (int column = 0; column < columnCount; column++) {
                    String value = neutralizeFormula(record.get(column));
                    row.createCell(column).setCellValue(value);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] renderPptx(String content) throws IOException {
        String[] slideContents = content.split("(?m)^---\\s*$");
        if (slideContents.length > 100) {
            throw new DocumentEditException("Presentation exceeds slide limit", "幻灯片超过 100 页，无法生成");
        }
        try (XMLSlideShow presentation = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            presentation.setPageSize(new Dimension(960, 540));
            for (String slideContent : slideContents) {
                String trimmed = slideContent.strip();
                if (trimmed.isBlank()) {
                    continue;
                }
                String[] lines = trimmed.split("\\R", 2);
                String title = lines[0].replaceFirst("^#+\\s*", "");
                String body = lines.length > 1 ? lines[1].strip() : "";
                XSLFSlide slide = presentation.createSlide();
                XSLFTextBox titleBox = slide.createTextBox();
                titleBox.setAnchor(new Rectangle2D.Double(55, 35, 850, 90));
                titleBox.setText(title);
                titleBox.getTextParagraphs().getFirst().setTextAlign(TextParagraph.TextAlign.LEFT);
                titleBox.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontSize(28d);
                XSLFTextBox bodyBox = slide.createTextBox();
                bodyBox.setAnchor(new Rectangle2D.Double(65, 140, 830, 360));
                bodyBox.setText(body);
                bodyBox.getTextParagraphs().forEach(paragraph -> paragraph.getTextRuns()
                        .forEach(run -> run.setFontSize(18d)));
            }
            if (presentation.getSlides().isEmpty()) {
                presentation.createSlide();
            }
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] renderPdf(String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = PdfHandler.getDocumentHandler().create();
        try {
            // x-easypdf 内置鸿蒙字体，天然支持中文
            document.setFontName("SimHei");
            Page page = new Page(document);
            Textarea textarea = new Textarea(page);
            textarea.setText(content);
            // x-easypdf 自动处理换行和分页
            textarea.render();
            document.appendPage(page);
            document.save(output);
            return output.toByteArray();
        } finally {
            document.close();
        }
    }

    private static String neutralizeFormula(String value) {
        return value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")
                ? "'" + value
                : value;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new DocumentEditException("Model returned empty document content", "模型没有返回有效文件内容，请重新描述修改要求");
        }
        if (content.length() > MAX_TEXT_CHARS) {
            throw new DocumentEditException("Model output exceeds character limit", "修改后的内容过长，无法生成文件");
        }
        return content.replace("\u0000", "");
    }
}
