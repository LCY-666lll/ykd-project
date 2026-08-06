package com.fourth.ykd.ilink.service;

import com.fourth.ykd.exception.BusinessException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/*把 iLink SDK 返回的二维码字符串转成 PNG 图片字节*/
@Service
public class IlinkQrCodeService {

    /**
     * 360 × 360 像素。
     */
    private static final int IMAGE_SIZE = 360;

    /**
     * 将 iLink SDK 返回的二维码内容转换为 PNG 图片字节。
     * @param qrCodeContent iLink SDK 的 executeLogin() 返回值
     * @return PNG 格式的图片字节
     */
    public byte[] createPng(String qrCodeContent) {
        if (!StringUtils.hasText(qrCodeContent)) {
            throw new BusinessException(40010, "iLink 二维码内容不能为空");
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            //字符串 -> BitMatrix（二维码的黑白格子矩阵）
            BitMatrix matrix = new QRCodeWriter().encode(
                    qrCodeContent,
                    BarcodeFormat.QR_CODE,
                    IMAGE_SIZE,
                    IMAGE_SIZE,
                    Map.of(
                            EncodeHintType.CHARACTER_SET, "UTF-8",
                            EncodeHintType.MARGIN, 1
                    )
            );

            //BitMatrix -> ByteArrayOutputStream -> byte[] PNG
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (WriterException | IOException exception) {
            throw new BusinessException(50012, "iLink 二维码图片生成失败");
        }
    }
}