package com.fourth.ykd.ai.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 本地时间工具 — 使用 @Tool 声明式注解，提供给 Spring AI Alibaba Function Calling。
 * <p>实现思路：</p>
 * <ul>
 *     <li>@Tool 注解标注方法，向 AI 声明工具描述和参数</li>
 *     <li>直接读取服务器本地系统时间，无需调用任何第三方API、无需API Key</li>
 * </ul>
 *
 * <p>Bean 名称解析：</p>
 * <pre>
 * TimeTool + @Component 默认bean名称 timeTool
 * 无需额外配置，自动被Spring扫描
 * </pre>
 *
 * <p>使用方式：</p>
 * <pre>
 ChatClient.prompt("现在几点？距离国庆还有多少天？")
 .tools(timeTool)
 .content();
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeTool {

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 获取时间相关信息，当AI模型需要真实时间、日期计算时自动调用。
     * <p>适用场景：</p>
     * <ul>
     *     <li>查询当前日期、星期、当前时间</li>
     *     <li>计算两个日期相隔天数</li>
     *     <li>模型知识库存在时间截止，无法获取实时时间时调用此工具</li>
     * </ul>
     *
     * @param operateType 操作类型，支持：now(获取当前时间)、diff(日期差值计算)
     * @param targetDate  目标日期，operateType=diff时必填，格式 yyyy-MM-dd
     * @return 格式化文本结果，供AI二次加工展示
     */
    @Tool(description = """
            获取系统真实时间。
            当用户询问当前时间、今天日期、星期几，或者需要计算日期间隔时调用此工具。
            operateType=now：获取当前上海时区日期时间；
            operateType=diff：需要传入targetDate，计算今天与目标日期相隔天数。
            """)
    public String getTimeInfo(
            @ToolParam(description = "操作类型，可选值：now / diff") String operateType,
            @ToolParam(description = "目标日期，格式 yyyy-MM-dd；operateType为diff时填写") String targetDate
    ) {
        if (operateType == null || operateType.trim().isEmpty()) {
            return "operateType不能为空，可选值 now、diff";
        }
        operateType = operateType.trim();
        log.info("[TIME_TOOL] operateType={}, targetDate={}", operateType, targetDate);

        try {
            LocalDateTime now = LocalDateTime.now(ZONE_SHANGHAI);
            if ("now".equals(operateType)) {
                String nowStr = now.format(DATE_TIME_FORMATTER);
                return "当前上海时区时间：" + nowStr + "，星期" + now.getDayOfWeek().getValue();
            }
            if ("diff".equals(operateType)) {
                if (targetDate == null || targetDate.trim().isEmpty()) {
                    return "计算日期差值时targetDate不能为空，格式示例：2026-10-01";
                }
                LocalDate today = now.toLocalDate();
                LocalDate date = LocalDate.parse(targetDate.trim(), DATE_FORMATTER);
                long days = Duration.between(today.atStartOfDay(), date.atStartOfDay()).toDays();
                if (days > 0) {
                    return "距离 " + targetDate + " 还有 " + days + " 天";
                } else if (days < 0) {
                    return targetDate + " 已经过去 " + Math.abs(days) + " 天";
                } else {
                    return "今天就是 " + targetDate;
                }
            }
            return "不支持的operateType：" + operateType;
        } catch (Exception e) {
            log.warn("[TIME_TOOL] 执行异常：{}", e.getMessage(), e);
            return "时间工具暂时不可用，" + e.getMessage();
        }
    }
}