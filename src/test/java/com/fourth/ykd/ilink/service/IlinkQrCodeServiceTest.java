package com.fourth.ykd.ilink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IlinkQrCodeServiceTest {

    @Test
    void createsPngImage() {
        byte[] image = new IlinkQrCodeService().createPng("https://liteapp.weixin.qq.com/q/example");

        assertEquals((byte) 0x89, image[0]);
        assertEquals((byte) 0x50, image[1]);
        assertEquals((byte) 0x4E, image[2]);
        assertEquals((byte) 0x47, image[3]);
    }
}
