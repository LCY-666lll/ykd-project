package com.fourth.ykd.ilink.controller;

import com.fourth.ykd.ilink.service.IlinkQrCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ilink/qrcode")
@RequiredArgsConstructor
public class IlinkQrCodeController {

    private final IlinkQrCodeService ilinkQrCodeService;

    @GetMapping(value = "/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> render(@RequestParam String content) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(ilinkQrCodeService.createPng(content));
    }
}
