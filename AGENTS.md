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
- Weather API host now falls back to the configured default when a local or environment override is blank.

- 精简天气工具调用相关日志：QWeatherClient 成功请求日志降为 DEBUG；AiChatServiceImpl 统一计算器字段命名并清理导入空行；验证：mvn -q -DskipTests compile 通过。

- 统一时间与计算工具日志为 [AI][TOOL][工具名][START/SUCCESS/FAILED] 格式，并规范时间工具的操作类型校验与异常日志；验证：mvn -q -DskipTests compile 通过。

- 百度搜索工具新增日期范围解析：明确日期直接使用，今天、昨天、最近月份、今年或缺少年份的月份区间通过 TimeTool 获取上海当前日期后标准化为搜索词；验证：mvn -q -DskipTests compile 通过。

- 简化百度搜索实时链路：模型自动调用 BaiduSearchTool 后，工具固定先调用 TimeTool.getTimeInfo("now", null)，再携带真实当前时间执行百度搜索；删除日期范围解析逻辑；验证：mvn -q -DskipTests compile 通过。

- 优化聊天工具提示词和新闻工具描述：默认新闻优先总结全国信息并追问地区或详情，明确地区时直接查询当地新闻，首次摘要不展示链接，追问来源或链接时再展示；新闻请求禁止调用天气工具；验证：mvn -q -DskipTests compile 通过。

- 修复百度搜索空结果链路：搜索词仅携带上海日期；解析失败或空结果统一返回“实时搜索失败”并记录 FAILED；中文提示词禁止失败后重试或使用训练数据补答；验证：mvn -q -DskipTests compile 通过。

- 优化新闻摘要与工具日志：百度搜索默认请求8条并限制在5到10条；中文提示词要求每条新闻以标题、发生内容和关键影响或进展写成2到3句，禁止编造；翻译工具成功日志统一为 SUCCESS；验证：mvn -q -DskipTests compile 通过。
