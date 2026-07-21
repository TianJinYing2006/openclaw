package com.example.demo.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 文件识别服务 — 检测文件类型、提取文本内容
 * <p>支持纯文本文档：txt / md / csv / json / xml / yml / yaml / properties / log / 源码等，
 * 以及 PDF / Word / Excel / PowerPoint 文档。</p>
 */
@Service
public class FileRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(FileRecognitionService.class);

    /** 最大提取字符数（避免超出 LLM 上下文） */
    private static final int MAX_EXTRACT_CHARS = 5_000;

    /** 纯文本扩展名列表 */
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "md", "csv", "json", "xml", "yml", "yaml",
            "properties", "log", "cfg", "conf", "ini",
            "java", "py", "js", "ts", "html", "css", "xml", "sql",
            "sh", "bat", "ps1", "gradle", "toml", "lock"
    ));

    /** 二进制但可解析的扩展名列表 */
    private static final Set<String> BINARY_PARSEABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "pdf", "docx", "xlsx", "pptx"
    ));

    /**
     * 提取文件文本内容
     * @param fileBytes 文件字节
     * @param fileName  文件名（用于判断类型）
     * @return 提取结果，包含类型标签和文本内容
     */
    public FileContent extract(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            return new FileContent("empty", "文件为空", 0);
        }

        String ext = getExtension(fileName).toLowerCase();
        String typeLabel = getTypeLabel(ext);

        // 纯文本 → 编码检测 + 字符串解码
        if (TEXT_EXTENSIONS.contains(ext)) {
            return extractText(fileBytes, typeLabel);
        }

        // PDF → PDFBox 解析
        if (BINARY_PARSEABLE_EXTENSIONS.contains(ext)) {
            return switch (ext) {
                case "pdf" -> extractPdf(fileBytes, typeLabel);
                case "docx" -> extractDocx(fileBytes, typeLabel);
                case "xlsx" -> extractXlsx(fileBytes, typeLabel);
                case "pptx" -> extractPptx(fileBytes, typeLabel);
                default -> new FileContent(typeLabel, null, fileBytes.length);
            };
        }

        return new FileContent(typeLabel, null, fileBytes.length);
    }

    private FileContent extractText(byte[] fileBytes, String typeLabel) {
        String text = tryDecode(fileBytes, StandardCharsets.UTF_8);
        if (text == null) {
            text = tryDecode(fileBytes, Charset.forName("GBK"));
        }
        if (text == null) {
            return new FileContent(typeLabel, null, fileBytes.length);
        }
        text = truncate(text);
        return new FileContent(typeLabel, text, fileBytes.length);
    }

    private FileContent extractPdf(byte[] fileBytes, String typeLabel) {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            if (text == null || text.isBlank()) {
                return new FileContent(typeLabel, null, fileBytes.length);
            }
            text = truncate(text.trim());
            return new FileContent(typeLabel, text, fileBytes.length);
        } catch (Exception e) {
            log.warn("PDF 解析失败: {}", e.getMessage());
            return new FileContent(typeLabel, null, fileBytes.length);
        }
    }

    private FileContent extractPptx(byte[] fileBytes, String typeLabel) {
        try (InputStream is = new ByteArrayInputStream(fileBytes);
             XMLSlideShow ppt = new XMLSlideShow(is)) {
            StringBuilder sb = new StringBuilder();
            int slideNum = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                slideNum++;
                StringBuilder slideSb = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.isBlank()) {
                            slideSb.append(t.trim()).append("\n");
                        }
                    }
                }
                String slideText = slideSb.toString().trim();
                if (!slideText.isBlank()) {
                    sb.append("--- 第 ").append(slideNum).append(" 页 ---\n");
                    sb.append(slideText).append("\n");
                }
            }
            String text = sb.toString().trim();
            if (text.isBlank()) {
                return new FileContent(typeLabel, null, fileBytes.length);
            }
            return new FileContent(typeLabel, truncate(text), fileBytes.length);
        } catch (Exception e) {
            log.warn("PPTX 解析失败: {}", e.getMessage());
            return new FileContent(typeLabel, null, fileBytes.length);
        }
    }

    private FileContent extractXlsx(byte[] fileBytes, String typeLabel) {
        try (InputStream is = new ByteArrayInputStream(fileBytes);
             Workbook wb = new XSSFWorkbook(is)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                if (sheetName != null && !sheetName.isBlank()) {
                    sb.append("=== ").append(sheetName).append(" ===\n");
                }
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String cellValue = getCellValue(cell);
                        if (cellValue != null && !cellValue.isBlank()) {
                            sb.append(cellValue).append("\t");
                        }
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
            String text = sb.toString().trim();
            if (text.isBlank()) {
                return new FileContent(typeLabel, null, fileBytes.length);
            }
            return new FileContent(typeLabel, truncate(text), fileBytes.length);
        } catch (Exception e) {
            log.warn("XLSX 解析失败: {}", e.getMessage());
            return new FileContent(typeLabel, null, fileBytes.length);
        }
    }

    private FileContent extractDocx(byte[] fileBytes, String typeLabel) {
        try (InputStream is = new ByteArrayInputStream(fileBytes);
             XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().stream()
                    .map(p -> p.getText())
                    .filter(t -> t != null && !t.isBlank())
                    .forEach(t -> sb.append(t).append("\n"));
            String text = sb.toString().trim();
            if (text.isBlank()) {
                return new FileContent(typeLabel, null, fileBytes.length);
            }
            return new FileContent(typeLabel, truncate(text), fileBytes.length);
        } catch (Exception e) {
            log.warn("DOCX 解析失败: {}", e.getMessage());
            return new FileContent(typeLabel, null, fileBytes.length);
        }
    }

    private String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                // 避免 "1.0E10" 这种科学计数法，用整数输出
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    yield cell.getStringCellValue();
                }
            }
            default -> "";
        };
    }

    private String truncate(String text) {
        if (text.length() > MAX_EXTRACT_CHARS) {
            return text.substring(0, MAX_EXTRACT_CHARS)
                    + "\n\n... (内容过长，仅截取前 " + MAX_EXTRACT_CHARS + " 字符)";
        }
        return text;
    }

    /**
     * 构建 LLM 提示词
     */
    public String buildPrompt(String fileName, String text, String caption) {
        StringBuilder sb = new StringBuilder();
        if (caption != null && !caption.isBlank()) {
            sb.append("用户说：").append(caption).append("\n\n");
        }
        sb.append("用户发送了一个文件《").append(fileName).append("》，内容如下：\n\n");
        sb.append(text);
        sb.append("\n\n请根据以上内容回答用户的问题。");
        return sb.toString();
    }

    private String tryDecode(byte[] bytes, Charset charset) {
        try {
            String text = new String(bytes, charset);
            // 检查乱码程度：如果替换字符（\uFFFD）占比超过 5%，说明编码不对
            int replacementCount = 0;
            for (int i = 0; i < text.length() && i < 1000; i++) {
                if (text.charAt(i) == '\uFFFD') {
                    replacementCount++;
                }
            }
            int checkLen = Math.min(text.length(), 1000);
            if (checkLen > 0 && (double) replacementCount / checkLen > 0.05) {
                log.debug("编码 {} 乱码率过高 ({}/{})，尝试其他编码", charset, replacementCount, checkLen);
                return null;
            }
            // 去除空字节（二进制文件的残留）
            text = text.replace("\0", "");
            if (text.isBlank()) {
                return null;
            }
            return text;
        } catch (Exception e) {
            log.debug("编码 {} 解码失败: {}", charset, e.getMessage());
            return null;
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1);
    }

    private String getTypeLabel(String ext) {
        return switch (ext) {
            case "txt" -> "纯文本";
            case "md" -> "Markdown";
            case "csv" -> "CSV 表格";
            case "json" -> "JSON";
            case "xml" -> "XML";
            case "yml", "yaml" -> "YAML";
            case "properties" -> "配置";
            case "log" -> "日志";
            case "java" -> "Java 源码";
            case "py" -> "Python 源码";
            case "js" -> "JavaScript 源码";
            case "ts" -> "TypeScript 源码";
            case "html" -> "HTML";
            case "css" -> "CSS";
            case "pdf" -> "PDF 文档";
            case "docx" -> "Word 文档";
            case "xlsx" -> "Excel 表格";
            case "pptx" -> "PowerPoint 演示";
            default -> ext.isBlank() ? "未知" : ext.toUpperCase();
        };
    }

    /**
     * 文件提取结果
     * @param type      文件类型标签（如 "Markdown"、"纯文本"）
     * @param text      提取的文本内容（不可解析时返回 null）
     * @param fileSize  文件大小（字节）
     */
    public record FileContent(String type, String text, long fileSize) {
        public boolean isText() { return text != null; }
    }
}
