package com.fourth.ykd.ai.utils;

import com.fourth.ykd.ai.dto.GeneratedDocument;
import com.fourth.ykd.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

// 复用聊天记忆生成一次内容，再转换为用户要求的一种或多种文件。
@Component
public class FileGenerationTool {

    private final ChatClient springAiChatClient;
    private final String pdfChineseFontPath;

    public FileGenerationTool(
            ChatClient springAiChatClient,
            @Value("${file.pdf-chinese-font-path:C:/Windows/Fonts/STSONG.TTF}") String pdfChineseFontPath
    ) {
        this.springAiChatClient = springAiChatClient;
        this.pdfChineseFontPath = pdfChineseFontPath;
    }

    public List<GeneratedDocument> generate(String userId, String userText) {
        FileDraft draft = springAiChatClient.prompt().system("""
                你负责根据当前聊天历史、图片识别记忆和用户请求生成可下载文件。
                支持 DOCX、XLSX、PDF；可以同时生成多个格式。必须只返回 JSON：
                {"types":["DOCX","XLSX","PDF"],"title":"文件标题","content":"完整内容"}
                未明确格式时 types 返回 ["DOCX"]；XLSX 内容使用换行分隔记录、使用 | 分隔单元格；只输出 JSON，不要解释。
                """).user(userText.trim())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, userId)).call().entity(FileDraft.class);
        if (draft == null || !StringUtils.hasText(draft.content())) {
            throw new BusinessException(50006, "文件内容生成失败");
        }
        String title = StringUtils.hasText(draft.title()) ? draft.title().trim() : "文件内容";
        List<GeneratedDocument> result = new ArrayList<>();
        for (String type : normalizeTypes(draft.types())) {
            result.add(switch (type) {
                case "XLSX" -> createXlsx(title, draft.content());
                case "PDF" -> createPdf(title, draft.content());
                default -> createDocx(title, draft.content());
            });
        }
        return result;
    }

    private List<String> normalizeTypes(List<String> types) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String type : types == null ? List.of("DOCX") : types) {
            if (StringUtils.hasText(type) && ("DOCX".equalsIgnoreCase(type)
                    || "XLSX".equalsIgnoreCase(type) || "PDF".equalsIgnoreCase(type))) {
                result.add(type.trim().toUpperCase());
            }
        }
        return result.isEmpty() ? List.of("DOCX") : List.copyOf(result);
    }

    private GeneratedDocument createDocx(String title, String content) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(title);
            for (String line : content.split("\\R")) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(output);
            return new GeneratedDocument(output.toByteArray(), safeName(title) + ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } catch (IOException exception) {
            throw new BusinessException(50006, "DOCX 文件生成失败");
        }
    }

    private GeneratedDocument createXlsx(String title, String content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("内容");
            String[] lines = content.split("\\R");
            for (int rowIndex = 0; rowIndex < lines.length; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                String[] cells = lines[rowIndex].split("\\|", -1);
                for (int columnIndex = 0; columnIndex < cells.length; columnIndex++) {
                    row.createCell(columnIndex).setCellValue(cells[columnIndex].trim());
                }
            }
            workbook.write(output);
            return new GeneratedDocument(output.toByteArray(), safeName(title) + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException exception) {
            throw new BusinessException(50006, "XLSX 文件生成失败");
        }
    }

    // PDFBox 不自带中文字体，必须从配置路径加载可嵌入字体。
    private GeneratedDocument createPdf(String title, String content) {
        Path fontPath = Path.of(pdfChineseFontPath);
        if (!Files.isRegularFile(fontPath)) {
            throw new BusinessException(50006, "PDF 中文字体文件不存在，请配置 file.pdf-chinese-font-path");
        }
        try (PDDocument document = new PDDocument();
             InputStream fontStream = Files.newInputStream(fontPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            PdfPageWriter writer = new PdfPageWriter(document, font);
            writer.writeLine(title, 16);
            for (String line : content.split("\\R", -1)) {
                writer.writeWrappedLine(line, 11);
            }
            writer.close();
            document.save(output);
            return new GeneratedDocument(output.toByteArray(), safeName(title) + ".pdf", "application/pdf");
        } catch (IOException exception) {
            throw new BusinessException(50006, "PDF 文件生成失败");
        }
    }

    private String safeName(String title) {
        String result = title.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        return StringUtils.hasText(result) ? result.substring(0, Math.min(result.length(), 40)) : "文件内容";
    }

    public record FileDraft(List<String> types, String title, String content) {
    }

    private static final class PdfPageWriter {
        private static final float LEFT = 50;
        private static final float TOP = 792;
        private static final float BOTTOM = 50;
        private static final float LINE_HEIGHT = 18;
        private static final float WIDTH = 495;
        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream stream;
        private float y;

        private PdfPageWriter(PDDocument document, PDType0Font font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void writeWrappedLine(String line, int fontSize) throws IOException {
            if (!StringUtils.hasText(line)) {
                writeLine(" ", fontSize);
                return;
            }
            StringBuilder current = new StringBuilder();
            for (int index = 0; index < line.length(); index++) {
                current.append(line.charAt(index));
                if (font.getStringWidth(current.toString()) / 1000 * fontSize > WIDTH) {
                    current.deleteCharAt(current.length() - 1);
                    writeLine(current.toString(), fontSize);
                    current.setLength(0);
                    current.append(line.charAt(index));
                }
            }
            writeLine(current.toString(), fontSize);
        }

        private void writeLine(String line, int fontSize) throws IOException {
            if (y - LINE_HEIGHT < BOTTOM) {
                newPage();
            }
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(LEFT, y);
            stream.showText(line);
            stream.endText();
            y -= LINE_HEIGHT;
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = TOP;
        }

        private void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }
}
