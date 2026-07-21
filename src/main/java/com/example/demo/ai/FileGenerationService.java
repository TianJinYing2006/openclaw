package com.example.demo.ai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 文件生成服务 — 将文本内容生成为各种格式的文件
 * <p>支持：TXT / PDF / DOCX / XLSX</p>
 */
@Service
public class FileGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FileGenerationService.class);

    /**
     * 生成文本文件
     */
    public byte[] generateTxt(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成 PDF 文件（使用 PDFBox）
     */
    public byte[] generatePdf(String content, String title) {
        try (PDDocument document = new PDDocument()) {
            // 按页分割（每页约 50 行）
            String[] lines = content.split("\n");
            int linesPerPage = 50;
            int pageCount = (int) Math.ceil((double) lines.length / linesPerPage);
            if (pageCount == 0) pageCount = 1;

            for (int p = 0; p < pageCount; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    // 尝试加载中文字体，如果 fonts 目录下有字体文件则使用，否则用系统字体
                    PDType0Font font = loadChineseFont(document);
                    if (font == null) {
                        log.warn("未找到中文 TTF 字体文件，PDF 中文可能显示为方框");
                    }

                    cs.setFont(font, 11);
                    cs.beginText();
                    cs.newLineAtOffset(50, PDRectangle.A4.getHeight() - 50);
                    cs.setLeading(16);

                    // 第一页加标题
                    if (p == 0 && title != null && !title.isBlank()) {
                        cs.setFont(font, 16);
                        cs.showText(truncateText(title, 60));
                        cs.newLineAtOffset(0, -8);
                        cs.setFont(font, 11);
                        cs.newLineAtOffset(0, -8);
                    }

                    int start = p * linesPerPage;
                    int end = Math.min(start + linesPerPage, lines.length);
                    for (int i = start; i < end; i++) {
                        String line = lines[i];
                        // 处理空行
                        if (line.isBlank()) {
                            cs.newLine();
                            continue;
                        }
                        // 如果行太长则换行
                        while (line.length() > 80) {
                            cs.showText(truncateText(line.substring(0, 80), 80));
                            cs.newLine();
                            line = line.substring(80);
                        }
                        cs.showText(truncateText(line, 80));
                        cs.newLine();
                    }

                    cs.endText();
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            document.save(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("PDF 生成失败，降级为 TXT: {}", e.getMessage());
            return generateTxt(content);
        }
    }

    /**
     * 生成 Word 文档（DOCX，使用 POI）
     */
    public byte[] generateDocx(String content, String title) {
        try (XWPFDocument doc = new XWPFDocument()) {
            // 标题
            if (title != null && !title.isBlank()) {
                XWPFParagraph titlePara = doc.createParagraph();
                titlePara.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(title);
                titleRun.setBold(true);
                titleRun.setFontSize(16);
                titleRun.setFontFamily("Microsoft YaHei");
                titlePara.createRun().addBreak();
            }

            // 正文逐行
            String[] lines = content.split("\n");
            for (String line : lines) {
                XWPFParagraph para = doc.createParagraph();
                para.setSpacingBetween(1.2);
                XWPFRun run = para.createRun();
                run.setText(line.isBlank() ? " " : line);
                run.setFontSize(11);
                run.setFontFamily("Microsoft YaHei");
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("DOCX 生成失败，降级为 TXT: {}", e.getMessage());
            return generateTxt(content);
        }
    }

    /**
     * 生成 Excel 表格（XLSX，使用 POI）
     * <p>数据格式：第一行为表头，之后为数据行，每行为各列的值</p>
     */
    public byte[] generateXlsx(List<String[]> rows, String sheetName) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet(sheetName != null ? sheetName : "Sheet1");

            for (int i = 0; i < rows.size(); i++) {
                XSSFRow row = sheet.createRow(i);
                String[] cols = rows.get(i);
                for (int j = 0; j < cols.length; j++) {
                    XSSFCell cell = row.createCell(j);
                    cell.setCellValue(cols[j] != null ? cols[j] : "");
                }
            }

            // 自动调整列宽
            if (!rows.isEmpty()) {
                int colCount = rows.getFirst().length;
                for (int j = 0; j < colCount; j++) {
                    sheet.autoSizeColumn(j);
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("XLSX 生成失败，降级为 TXT: {}", e.getMessage());
            StringBuilder sb = new StringBuilder();
            for (String[] row : rows) {
                sb.append(String.join("\t", row)).append("\n");
            }
            return generateTxt(sb.toString());
        }
    }

    /**
     * 从 content 中解析出表格数据（每行 \n，每列 \t 或 |）
     */
    public List<String[]> parseTableFromContent(String content) {
        return Arrays.stream(content.split("\n"))
                .filter(line -> !line.isBlank())
                .map(line -> {
                    if (line.contains("\t")) {
                        return line.split("\t");
                    }
                    // 支持 Markdown 表格：| A | B | C |
                    String clean = line.replaceAll("^\\|\\s*|\\s*\\|$", "");
                    return clean.split("\\s*\\|\\s*");
                })
                .toList();
    }

    private String truncateText(String text, int maxLen) {
        // 简单截断，避免 PDFBox 因字体缺失字符崩溃
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    private PDType0Font loadChineseFont(PDDocument document) {
        // 尝试加载系统中文字体
        String[] fontPaths = {
                "C:/Windows/Fonts/msyh.ttc",         // 微软雅黑
                "C:/Windows/Fonts/simfang.ttf",       // 仿宋
                "C:/Windows/Fonts/simhei.ttf",        // 黑体
                "C:/Windows/Fonts/simsun.ttc",        // 宋体
                "C:/Windows/Fonts/msyhbd.ttc",        // 微软雅黑加粗
        };
        for (String path : fontPaths) {
            java.io.File fontFile = new java.io.File(path);
            if (fontFile.exists()) {
                try {
                    return PDType0Font.load(document, fontFile);
                } catch (Exception e) {
                    log.debug("字体 {} 加载失败: {}", path, e.getMessage());
                }
            }
        }
        // 无中文字体时返回 null，generatePdf 中处理降级
        return null;
    }
}
