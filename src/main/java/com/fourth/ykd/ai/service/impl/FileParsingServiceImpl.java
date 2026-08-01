package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.service.FileParsingService;
import com.fourth.ykd.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FileParsingServiceImpl implements FileParsingService {

    @Override
    public String parse(String fileName, byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(40001, "文件内容为空");
        }

        String ext = getExtension(fileName);
        try (InputStream is = new ByteArrayInputStream(fileBytes)) {
            return switch (ext) {
                case "pdf" -> parsePdf(is);
                case "docx" -> parseDocx(is);
                case "xlsx" -> parseXlsx(is);
                default -> throw new BusinessException(40001, "不支持的文件格式: " + ext);
            };
        } catch (IOException e) {
            throw new BusinessException(50006, "文件解析失败: " + e.getMessage());
        }
    }

    private String parsePdf(InputStream is) throws IOException {
        byte[] bytes = is.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String parseDocx(InputStream is) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            return doc.getParagraphs().stream()
                    .map(p -> p.getText())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n"));
        }
    }

    private String parseXlsx(InputStream is) throws IOException {
        try (Workbook wb = WorkbookFactory.create(is)) {
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : wb) {
                sheet.forEach(row -> {
                    row.forEach(cell -> sb.append(cell.toString()).append("\t"));
                    sb.append("\n");
                });
            }
            return sb.toString();
        }
    }

    private String getExtension(String fileName) {
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}