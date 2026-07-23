package com.fourth.ykd.ai.utils;

import com.googlecode.aviator.AviatorEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;


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
        log.info("[AI][TOOL][MATH][START] expression={}", expression);
        try {
            Object result = AviatorEvaluator.execute(expression);
            log.info("[AI][TOOL][MATH][SUCCESS] expression={}, result={}", expression, result);
            return "表达式【" + expression + "】计算结果 = " + result;
        } catch (Exception e) {
            log.warn("[AI][TOOL][MATH][FAILED] expression={}, reason={}", expression, e.getMessage());
            return "表达式计算失败：" + e.getMessage();
        }
    }
}