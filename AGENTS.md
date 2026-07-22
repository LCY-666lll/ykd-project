# AGENTS.md

## Collaboration Rules

- Explain scope and reason before changing code, then wait for approval.
- Keep changes minimal and do not refactor unrelated business flows.
- Prompts, comments, and business exception messages in code must be Chinese.
- Update this file after every code change with the scope and verification result.

## Current AI Selection

- DeepSeek remains responsible for intent routing and normal text chat through Spring AI ChatClient.
- Qwen text-to-image and image-to-image use the official Spring AI Alibaba ImageModel.
- Qwen image understanding uses the official Spring AI OpenAI-compatible ChatModel against DashScope compatible-mode with Media, while the DeepSeek default text model remains unchanged.
- Qwen speech synthesis uses DashScopeAudioSpeechModel, TextToSpeechPrompt, and DashScopeAudioSpeechOptions.

## Change Record: 2026-07-22

- Upgraded Spring AI Alibaba to 1.1.2.2.
- Removed handwritten QwenImageClient, QwenVisionClient, QwenAudioTtsClient, and DashScopeProperties.
- Migrated image generation, image understanding, and speech synthesis services to official Spring AI Alibaba model calls.
- Corrected the TTS prefix to spring.ai.dashscope.audio.speech and uses longhua with a 48000 Hz sample rate.
- Verification: mvn -q -DskipTests compile passed.
- Speech verification: official streaming CosyVoice integration test passed with MP3 header validation.
- Vision verification: switched to the official OpenAI-compatible Spring AI wrapper recommended by Spring AI Alibaba for DashScope compatible-mode.
- Disabled unused OpenAI starter auto-configurations; only the explicit DashScope compatible vision model is retained.
- Vision verification: official OpenAI-compatible DashScope call passed with a 16 by 16 PNG test image.
- Image generation verification: official DashScope SDK ImageModel integration test passed.
- Audio verification: official streaming CosyVoice integration test passed with MP3 header validation.
- Final verification: DASHSCOPE_INTEGRATION_TEST=true mvn -q -Dtest=DashScopeOfficialModelIntegrationTest test passed for image generation, image understanding, and speech synthesis.
- Added an integration case for official SDK reference-image generation before final acceptance.
- Final verification: DASHSCOPE_INTEGRATION_TEST=true mvn -q -Dtest=DashScopeOfficialModelIntegrationTest test passed for text-to-image, reference-image generation, image understanding, and speech synthesis.
- Added concise Chinese comments to the new Qwen configuration, service implementations, and integration test.
- Added concise Chinese class-level comments to the Qwen configuration, service implementations, and integration test.
- Removed unused legacy DeepSeek client/config/properties and DashScope download config; image services now use RestClient.Builder.
