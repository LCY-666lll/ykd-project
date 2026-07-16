package com.fourth.ykd.ilink.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class IlinkQrCodeService {

    private static final int IMAGE_SIZE = 360;

    public byte[] createPng(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("QR-code content must not be blank");
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            BitMatrix matrix = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, IMAGE_SIZE, IMAGE_SIZE);
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("Unable to render iLink QR code", exception);
        }
    }
}
