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

@Slf4j
@Component
@RequiredArgsConstructor
public class TimeTool {

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 获取时间相关信息，当AI模型需要真实时间、日期计算时自动调用。
     * @param operateType 操作类型，支持：now(获取当前时间)、diff(日期差值计算)
     * @param targetDate  目标日期，operateType=diff时必填，格式 yyyy-MM-dd
     * @return 格式化文本结果，供AI二次加工展示
     */
    @Tool(name = "get_time_info", description = """
            获取系统真实时间，或计算当前日期与目标日期的间隔。
            operateType=now：获取上海时区当前日期时间；
            operateType=diff：传入 targetDate，计算当前日期与目标日期相隔天数。
            """)
    public String getTimeInfo(
            @ToolParam(description = "操作类型，可选值：now / diff", required = true) String operateType,
            @ToolParam(description = "目标日期，格式 yyyy-MM-dd；仅 operateType=diff 时填写，now 时不要填写",
                    required = false)
            String targetDate
    ) {
        if (operateType == null || operateType.trim().isEmpty()) {
            return "operateType不能为空，可选值 now、diff";
        }
        String normalizedOperateType = operateType.trim();
        log.info("[AI][TOOL][TIME][START] operateType={}, targetDate={}", normalizedOperateType, targetDate);

        try {
            LocalDateTime now = LocalDateTime.now(ZONE_SHANGHAI);
            if ("now".equals(normalizedOperateType)) {
                String nowStr = now.format(DATE_TIME_FORMATTER);
                String result = "当前上海时区时间：" + nowStr + "，星期" + now.getDayOfWeek().getValue();
                log.info("[AI][TOOL][TIME][SUCCESS] operateType={}, result={}", normalizedOperateType, result);
                return result;
            }
            if ("diff".equals(normalizedOperateType)) {
                if (targetDate == null || targetDate.trim().isEmpty()) {
                    return "计算日期差值时targetDate不能为空，格式示例：2026-10-01";
                }
                LocalDate today = now.toLocalDate();
                LocalDate date = LocalDate.parse(targetDate.trim(), DATE_FORMATTER);
                long days = Duration.between(today.atStartOfDay(), date.atStartOfDay()).toDays();
                String result;
                if (days > 0) {
                    result = "距离 " + targetDate + " 还有 " + days + " 天";
                } else if (days < 0) {
                    result = targetDate + " 已经过" + Math.abs(days) + " 天";
                } else {
                    result = "今天就是 " + targetDate;
                }
                log.info("[AI][TOOL][TIME][SUCCESS] operateType={}, targetDate={}, result={}",
                        normalizedOperateType, targetDate, result);
                return result;
            }
            log.warn("[AI][TOOL][TIME][FAILED] operateType={}, reason=不支持的操作类型", normalizedOperateType);
            return "不支持的operateType：" + normalizedOperateType;
        } catch (Exception e) {
            log.warn("[AI][TOOL][TIME][FAILED] operateType={}, targetDate={}, reason={}",
                    normalizedOperateType, targetDate, e.getMessage());
            return "时间工具暂时不可用，" + e.getMessage();
        }
    }
}