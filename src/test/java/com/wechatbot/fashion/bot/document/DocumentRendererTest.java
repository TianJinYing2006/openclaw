package com.wechatbot.fashion.bot.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentRendererTest {

    private final DocumentRenderer renderer = new DocumentRenderer();

    @Test
    void validatesStructuredTextFormats() {
        assertThat(new String(renderer.render("json", "{\"ok\":true}"), StandardCharsets.UTF_8))
                .contains("\"ok\" : true");
        assertThat(new String(renderer.render("xml", "<root>有效</root>"), StandardCharsets.UTF_8))
                .contains("有效");
        assertThatThrownBy(() -> renderer.render("json", "not-json"))
                .isInstanceOf(DocumentEditException.class);
        assertThatThrownBy(() -> renderer.render("xml", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>"))
                .isInstanceOf(DocumentEditException.class);
        assertThat(new String(renderer.render("csv", "name,value\ntest,=1+1"), StandardCharsets.UTF_8))
                .contains("'=1+1");
        assertThatThrownBy(() -> renderer.applyOperations("xlsx", new byte[]{1}, "{\"operations\":[]}"))
                .isInstanceOf(DocumentEditException.class)
                .extracting(exception -> ((DocumentEditException) exception).userMessage())
                .asString().contains("文件没有被修改", "修改：把具体原文改成具体新内容");
    }

    @Test
    void createsReadableDocxXlsxPptxAndPdf() throws Exception {
        byte[] docx = renderer.render("docx", "# 标题\n正文内容");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertThat(document.getParagraphs()).extracting(paragraph -> paragraph.getText())
                    .contains("标题", "正文内容");
        }

        byte[] xlsx = renderer.render("xlsx", "姓名,分数\n张三,95");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("张三");
        }

        byte[] pptx = renderer.render("pptx", "# 第一页\n内容一\n---\n# 第二页\n内容二");
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertThat(presentation.getSlides()).hasSize(2);
        }

        byte[] pdf = renderer.render("pdf", "中文标题\nPDF 正文");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("中文标题", "PDF 正文");
        }
    }

    @Test
    void officeOperationsPreserveUnrelatedDocumentStructures() throws Exception {
        byte[] originalDocx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("旧标题");
            document.createParagraph().createRun().setText("不应变化的正文");
            document.createTable(1, 1).getRow(0).getCell(0).setText("表格保留");
            document.write(output);
            originalDocx = output.toByteArray();
        }
        byte[] editedDocx = renderer.applyOperations("docx", originalDocx,
                "{\"operations\":[{\"type\":\"replace_text\",\"find\":\"旧标题\",\"replace\":\"新标题\"}]}");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(editedDocx))) {
            assertThat(document.getParagraphs()).extracting(paragraph -> paragraph.getText())
                    .containsExactly("新标题", "不应变化的正文");
            assertThat(document.getTables()).hasSize(1);
            assertThat(document.getTables().getFirst().getText()).contains("表格保留");
        }

        byte[] originalXlsx;
        short preservedStyle;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var data = workbook.createSheet("数据");
            data.createRow(0).createCell(0).setCellValue("旧值");
            var formulaCell = data.createRow(1).createCell(0);
            formulaCell.setCellFormula("1+1");
            var style = workbook.createCellStyle();
            style.setFillForegroundColor((short) 42);
            formulaCell.setCellStyle(style);
            preservedStyle = style.getIndex();
            workbook.createSheet("保留页").createRow(0).createCell(0).setCellValue("不要删除");
            workbook.write(output);
            originalXlsx = output.toByteArray();
        }
        byte[] editedXlsx = renderer.applyOperations("xlsx", originalXlsx,
                "{\"operations\":[{\"type\":\"set_cell\",\"sheet\":\"数据\",\"cell\":\"A1\",\"value\":100}]}");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(editedXlsx))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheet("数据").getRow(0).getCell(0).getNumericCellValue()).isEqualTo(100);
            assertThat(workbook.getSheet("数据").getRow(1).getCell(0).getCellFormula()).isEqualTo("1+1");
            assertThat(workbook.getSheet("数据").getRow(1).getCell(0).getCellStyle().getIndex())
                    .isEqualTo(preservedStyle);
            assertThat(workbook.getSheet("保留页").getRow(0).getCell(0).getStringCellValue()).isEqualTo("不要删除");
        }

        byte[] originalPptx;
        try (XMLSlideShow presentation = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var first = presentation.createSlide();
            var firstText = first.createTextBox();
            firstText.setAnchor(new Rectangle2D.Double(10, 10, 300, 80));
            firstText.setText("旧标题");
            var second = presentation.createSlide();
            var secondText = second.createTextBox();
            secondText.setText("不应变化的第二页");
            secondText.setFillColor(Color.YELLOW);
            presentation.write(output);
            originalPptx = output.toByteArray();
        }
        byte[] editedPptx = renderer.applyOperations("pptx", originalPptx,
                "{\"operations\":[{\"type\":\"replace_text\",\"find\":\"旧标题\",\"replace\":\"新标题\"}]}");
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(editedPptx))) {
            assertThat(presentation.getSlides()).hasSize(2);
            assertThat(presentation.getSlides().getFirst().getShapes().getFirst()).extracting("text").isEqualTo("新标题");
            assertThat(presentation.getSlides().get(1).getShapes().getFirst()).extracting("text")
                    .isEqualTo("不应变化的第二页");
        }
    }

    @Test
    void docxFullRewriteReplacesInsteadOfAppendingToTheOriginal() throws Exception {
        byte[] original = renderer.render("docx", "# 原销售计划\n旧目标\n旧措施");

        byte[] rewritten = renderer.applyOperations("docx", original,
                "{\"operations\":[{\"type\":\"replace_document\",\"content\":\"# 新销售工作计划\\n## 目标\\n提升客户转化率\\n## 措施\\n完善回访机制\"}]}");

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(rewritten))) {
            assertThat(document.getParagraphs()).extracting(paragraph -> paragraph.getText())
                    .contains("新销售工作计划", "目标", "提升客户转化率", "措施", "完善回访机制")
                    .doesNotContain("原销售计划", "旧目标", "旧措施");
        }
    }
}
