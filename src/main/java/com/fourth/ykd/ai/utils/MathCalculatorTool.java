package com.fourth.ykd.ai.utils;

import com.googlecode.aviator.AviatorEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;



/**
 * 本地表达式计算器工具 — 使用 @Tool 声明式注解，提供给 Spring AI Alibaba Function Calling。
 * <p>实现思路：</p>
 * <ul>
 *     <li>@Tool 注解标注方法，向 AI 声明工具描述和参数</li>
 *     <li>本地使用Aviator执行数学表达式，无外部API调用、无需API Key</li>
 * </ul>
 *
 * <p>Bean 名称解析：</p>
 * <pre>
 MathCalculatorTool + @Component 默认bean名称 mathCalculatorTool
 * 无需额外配置，自动被Spring扫描
 * </pre>
 *
 * <p>使用方式：</p>
 * <pre>
 ChatClient.prompt("计算 (12.5+23)*8/2")
 .tools(mathCalculatorTool)
 .content();
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MathCalculatorTool {

    /**
     * 数学表达式运算，当AI需要精确数学计算时自动调用。
     * <p>适用场景：</p>
     * <ul>
     *     <li>四则运算、小数计算、复杂算式求解</li>
     *     <li>防止大模型口算出错，交由本地代码精确运算</li>
     * </ul>
     *
     * @param expression 数学表达式，例如 (100+25)*3/2
     * @return 计算结果文本，供AI二次加工展示
     */
    @Tool(description = """
            执行数学表达式精确计算。
            用户提出算式、数值计算问题时调用。
            不要让AI自行估算，优先调用本工具得到准确结果。
            """)
    public String calculate(
            @ToolParam(description = "待计算的数学表达式，例如 (128.5 + 231.5) * 12 / 4") String expression
    ) {
        if (expression == null || expression.trim().isEmpty()) {
            return "表达式不能为空";
        }
        expression = expression.trim();
        log.info("[MATH_TOOL] expression={}", expression);
        try {
            Object result = AviatorEvaluator.execute(expression);
            log.info("[MATH_TOOL] 计算成功，结果={}", result);
            return "表达式【" + expression + "】计算结果 = " + result;
        } catch (Exception e) {
            log.warn("[MATH_TOOL] 计算异常：{}", e.getMessage(), e);
            return "表达式计算失败：" + e.getMessage();
        }
    }
}