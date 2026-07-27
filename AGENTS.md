修复实时天气上下文误用：提示词要求本轮明确查询当前、实时天气时必须重新调用 WeatherTool；修复识图后图片上下文被清理，允许后续图片编辑；验证：mvn -q -DskipTests compile 通过。
