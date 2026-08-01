package com.fourth.ykd.ai.service;

/**
 * 文件内容解析服务，支持 PDF、DOCX、XLSX。
 */
public interface FileParsingService {

    /**
     * 解析文件内容
     * @param fileName 文件名（用于判断类型）
     * @param fileBytes 文件字节
     * @return 解析出的文本内容
     */
    String parse(String fileName, byte[] fileBytes);
}