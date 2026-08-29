package com.wechatbot.fashion.bot.document;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsDocxParagraphsAndTables() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("公司简介正文");
            document.createTable(1, 2).getRow(0).getCell(0).setText("名称");
            document.getTables().getFirst().getRow(0).getCell(1).setText("测试公司");
            document.write(output);
            bytes = output.toByteArray();
        }

        assertThat(extractor.extract("docx", bytes)).get().asString()
                .contains("公司简介正文", "名称", "测试公司");
    }

    @Test
    void extractsWorkbookSheetsCellsAndFormulas() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("营业额");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThat(extractor.extract("xlsx", bytes)).get().asString()
                .contains("[工作表：数据]", "A1=营业额", "A2==1+1");
        assertThat(extractor.extract("doc", bytes)).isEmpty();
    }
}
