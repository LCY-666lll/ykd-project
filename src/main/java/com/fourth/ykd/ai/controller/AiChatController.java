package com.fourth.ykd.ai.controller;

import com.fourth.ykd.ai.dto.AiChatRequest;
import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.result.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/chat")
    public ApiResponse< AiChatResponse> chat(@RequestBody AiChatRequest request) {
        return ApiResponse.success(aiChatService.chat(request.message()));
    }
}