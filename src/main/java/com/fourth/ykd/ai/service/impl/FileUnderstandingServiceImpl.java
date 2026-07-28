package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.PendingUserFile;
import com.fourth.ykd.ai.service.FileUnderstandingService;
import com.fourth.ykd.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 文件内容理解服务 — 提取文件文本内容，通过 AI 生成摘要供聊天记忆使用。
 * 复用已有的 POI / PDFBox 依赖（与 FileGenerationTool 共用）。
 */
@Slf4j
@Service
public class FileUnderstandingServiceImpl implements FileUnderstandingService {

    private static final int MAX_EXTRACTED_CHARS = 8000;

    private final ChatClient springAiChatClient;

    public FileUnderstandingServiceImpl(ChatClient springAiChatClient) {
        this.springAiChatClient = springAiChatClient;
    }

    @Override
    public String understand(PendingUserFile file) {
        if (file == null || file.bytes() == null || file.bytes().length == 0) {
            throw new BusinessException(40001, "待识别文件不能为空");
        }

        String rawText = extractText(file);
        if (!StringUtils.hasText(rawText)) {
            return "文件「" + file.fileName() + "」未能提取到可读文本内容。";
        }

        String summary = springAiChatClient.prompt()
                .system("""
                        你是一个文件内容分析助手。根据提取到的文件原始文本，
                        生成结构化的中文摘要。包括：
                        - 文件类型和主题
                        - 关键内容的要点列表（3-5条）
                        回答简洁有结构化，适合作为聊天记录中的长期记忆。
                        """)
                .user("文件名：%s\n\n文件原始文本内容：\n%s".formatted(file.fileName(), rawText))
                .call()
                .content();

        return StringUtils.hasText(summary) ? summary : "文件「" + file.fileName() + "」已读取但未生成有效摘要。";
    }

    private String extractText(PendingUserFile file) {
        String fileName = file.fileName() != null ? file.fileName().toLowerCase() : "";
        try {
            if (fileName.endsWith(".txt")) {
                return truncate(new String(file.bytes(), StandardCharsets.UTF_8));
            }
            if (fileName.endsWith(".docx")) {
                return extractDocxText(file.bytes());
            }
            if (fileName.endsWith(".xlsx")) {
                return extractXlsxText(file.bytes());
            }
            if (fileName.endsWith(".pdf")) {
                return extractPdfText(file.bytes());
            }
            return new String(file.bytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[文件识别][提取文本][失败] fileName={}, reason={}", file.fileName(), e.getMessage());
            return null;
        }
    }

    private String extractDocxText(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append('\n'));
            doc.getTables().forEach(table ->
                table.getRows().forEach(row ->
                    row.getTableCells().forEach(cell -> sb.append(cell.getText()).append('\t'))
                )
            );
            return truncate(sb.toString());
        }
    }

    private String extractXlsxText(byte[] bytes) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            wb.forEach(sheet -> {
                sb.append("【").append(sheet.getSheetName()).append("】\n");
                int rowLimit = Math.min(sheet.getLastRowNum() + 1, 200);
                for (int i = 0; i < rowLimit; i++) {
                    var row = sheet.getRow(i);
                    if (row == null) continue;
                    row.forEach(cell -> sb.append(cell.toString()).append(" | "));
                    sb.append('\n');
                }
            });
            return truncate(sb.toString());
        }
    }

    private String extractPdfText(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return truncate(new PDFTextStripper().getText(doc));
        }
    }

    private String truncate(String text) {
        return text.length() > MAX_EXTRACTED_CHARS
                ? text.substring(0, MAX_EXTRACTED_CHARS) + "\n...(内容已截断)"
                : text;
    }
}
