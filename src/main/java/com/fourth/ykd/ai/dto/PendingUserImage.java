package com.fourth.ykd.ai.dto;

import java.time.Instant;

public record   PendingUserImage(byte[] bytes, String contentType, Instant receivedAt) {
}