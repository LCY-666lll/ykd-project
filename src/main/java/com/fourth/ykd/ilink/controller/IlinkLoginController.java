package com.fourth.ykd.ilink.controller;

import com.fourth.ykd.result.ApiResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginQrResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginStatusResponse;
import com.fourth.ykd.ilink.service.IlinkLoginService;
import com.fourth.ykd.ilink.service.IlinkQrCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ilink/login")
@RequiredArgsConstructor
public class IlinkLoginController {

    private final IlinkLoginService ilinkLoginService;
    private final IlinkQrCodeService ilinkQrCodeService;

    /**
     * 本地联调入口：
     * 打开此地址会发起新的 iLink 登录，并直接返回 PNG 二维码。
     */
    @GetMapping(value = "/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> startLoginAndRenderQrCode() {
        IlinkLoginQrResponse loginResponse = ilinkLoginService.startLogin();

        byte[] imageBytes = ilinkQrCodeService.createPng(
                loginResponse.qrCodeContent()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(imageBytes);
    }

    /**
     * 查询当前二维码的扫码/登录状态。
     * 这个接口只读状态，绝不会创建新二维码。
     */
    @GetMapping("/status")
    public ApiResponse<IlinkLoginStatusResponse> getLoginStatus() {
        return ApiResponse.success(ilinkLoginService.getLoginStatus());
    }

    /**
     * 手动取消当前扫码流程。
     */
    @PostMapping("/cancel")
    public ApiResponse<Void> cancelLogin() {
        ilinkLoginService.cancelLogin();
        return ApiResponse.success(null);
    }
}