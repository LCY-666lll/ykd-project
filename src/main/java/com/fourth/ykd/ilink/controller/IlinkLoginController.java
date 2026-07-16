package com.fourth.ykd.ilink.controller;

import com.fourth.ykd.common.ApiResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginQrResponse;
import com.fourth.ykd.ilink.dto.IlinkLoginStatusResponse;
import com.fourth.ykd.ilink.service.IlinkLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ilink/login")
@RequiredArgsConstructor
public class IlinkLoginController {

    private final IlinkLoginService ilinkLoginService;

    @PostMapping("/qrcode")
    public ApiResponse<IlinkLoginQrResponse> startLogin() {
        return ApiResponse.success(ilinkLoginService.startLogin());
    }

    @GetMapping("/status")
    public ApiResponse<IlinkLoginStatusResponse> getLoginStatus() {
        return ApiResponse.success(ilinkLoginService.getLoginStatus());
    }

    @PostMapping("/cancel")
    public ApiResponse<Void> cancelLogin() {
        ilinkLoginService.cancelLogin();
        return ApiResponse.success(null);
    }
}
