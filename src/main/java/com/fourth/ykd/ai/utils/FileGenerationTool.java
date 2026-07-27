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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
public class FileGenerationTool {

    private final ChatClient springAiChatClient;
    private final String pdfChineseFontPath;
    private final BaiduSearchTool baiduSearchTool;
    private final MathCalculatorTool mathCalculatorTool;
    private final TimeTool timeTool;
    private final TranslationTool translationTool;
    private final WeatherTool weatherTool;

    public FileGenerationTool(
            ChatClient springAiChatClient,
            BaiduSearchTool baiduSearchTool,
            MathCalculatorTool mathCalculatorTool,
            TimeTool timeTool,
            TranslationTool translationTool,
            WeatherTool weatherTool,
            @Value("${file.pdf-chinese-font-path:C:/Windows/Fonts/STSONG.TTF}") String pdfChineseFontPath
    ) {
        this.springAiChatClient = springAiChatClient;
        this.baiduSearchTool = baiduSearchTool;
        this.mathCalculatorTool = mathCalculatorTool;
        this.timeTool = timeTool;
        this.translationTool = translationTool;
        this.weatherTool = weatherTool;
        this.pdfChineseFontPath = pdfChineseFontPath;
    }

    public List<GeneratedDocument> generate(String userId, String userText) {
        log.info("[AI][FILE_GENERATE][START] userId={}, userText={}", userId, userText);
        FileDraft draft = springAiChatClient.prompt().system("""
                你负责根据当前聊天历史、图片识别记忆和用户请求生成可下载文件。
                支持 DOCX、XLSX、PDF；可以同时生成多个格式。必须只返回 JSON：
                {"types":["DOCX","XLSX","PDF"],"title":"文件标题","content":"完整内容"}
                用户查询现在、当前、实时天气或今天此刻天气时，必须调用 query_current_weather。
                用户查询今天至后天的最高最低温、每日预报或未来3天天气时，必须调用 query_weather_forecast。
                用户要求查询新闻、时事、最近、今天、昨天、上个月或指定日期范围的信息时，必须调用 search_realtime_information。
                用户提出算式或精确数值计算时，必须调用 calculate_math_expression。
                用户查询真实当前日期、时间或日期间隔时，必须调用 get_time_info。
                用户明确要求翻译时，必须调用 translate_text。
                工具调用失败时不得使用训练数据编造实时结果。
                用户明确指定 PDF、DOCX/Word、XLSX/Excel/表格时，types 必须严格返回用户指定的全部格式。
                未明确格式时 types 返回 ["DOCX"]；XLSX 内容使用换行分隔记录、使用 | 分隔单元格；只输出 JSON，不要解释。
                """).user(userText.trim())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, userId))
                .tools(mathCalculatorTool, timeTool, baiduSearchTool, weatherTool, translationTool)
                .call()
                .entity(FileDraft.class);
        if (draft == null || !StringUtils.hasText(draft.content())) {
            throw new BusinessException(50006, "文件内容生成失败");
        }
        String title = StringUtils.hasText(draft.title()) ? draft.title().trim() : "文件内容";
        List<GeneratedDocument> result = new ArrayList<>();
        for (String type : resolveTypes(userText, draft.types())) {
            result.add(switch (type) {
                case "XLSX" -> createXlsx(title, draft.content());
                case "PDF" -> createPdf(title, draft.content());
                default -> createDocx(title, draft.content());
            });
        }
        return result;
    }

    /**
     * 用户明确指定的格式优先于模型返回值，避免模型字段异常时静默生成错误文件类型。
     */
    List<String> resolveTypes(String userText, List<String> modelTypes) {
        String normalizedText = userText == null ? "" : userText.toUpperCase();
        LinkedHashSet<String> explicitTypes = new LinkedHashSet<>();
        if (normalizedText.contains("DOCX") || normalizedText.contains("WORD")) {
            explicitTypes.add("DOCX");
        }
        if (normalizedText.contains("XLSX") || normalizedText.contains("EXCEL")
                || normalizedText.matches("(?s).*(生成|导出|制作|创建).{0,6}表格.*")
                || normalizedText.matches("(?s).*表格.{0,6}(文件|格式).*")) {
            explicitTypes.add("XLSX");
        }
        if (normalizedText.contains("PDF")) {
            explicitTypes.add("PDF");
        }
        return explicitTypes.isEmpty() ? normalizeTypes(modelTypes) : List.copyOf(explicitTypes);
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
            String pdfTitle = sanitizePdfText(font, title);
            writer.writeLine(StringUtils.hasText(pdfTitle) ? pdfTitle : "文件内容", 16);
            for (String line : content.split("\\R", -1)) {
                writer.writeWrappedLine(sanitizePdfText(font, line), 11);
            }
            writer.close();
            document.save(output);
            return new GeneratedDocument(output.toByteArray(), safeName(title) + ".pdf", "application/pdf");
        } catch (IOException exception) {
            throw new BusinessException(50006, "PDF 文件生成失败");
        }
    }

    // PDF 字体不支持表情等字符时移除，避免宽度计算和文本写入失败。
    private String sanitizePdfText(PDType0Font font, String text) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            try {
                font.encode(character);
                result.append(character);
            } catch (IllegalArgumentException ignored) {
                // 忽略当前字体无法渲染的字符。
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
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
