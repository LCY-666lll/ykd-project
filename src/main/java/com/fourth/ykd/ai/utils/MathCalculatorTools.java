package com.fourth.ykd.ai.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class MathCalculatorTools {
    private static final Logger log = LoggerFactory.getLogger(MathCalculatorTools.class);
    @Tool(description = "执行数学四则运算：加法、减法、乘法、除法；接收两个数字、一个运算符，返回计算结果；除数为0时返回错误信息")
    public String calculate(
            @ToolParam(description = "第一个参与运算的数字", required = true) Double num1,
            @ToolParam(description = "第二个参与运算的数字", required = true) Double num2,
            @ToolParam(description = "运算符，仅允许 + 、 - 、 * 、 / 四种取值", required = true) String operator
    ) {
        log.info("[FunctionCall][计算器工具开始执行] num1={}, num2={}, operator={}", num1, num2, operator);

        double res;
        switch (operator) {
            case "+" -> res = num1 + num2;
            case "-" -> res = num1 - num2;
            case "*" -> res = num1 * num2;
            case "/" -> {
                if (num2 == 0) {
                    return "运算失败：除数不能为0";
                }
                res = num1 / num2;
            }
            default -> {
                return "运算失败：仅支持 + - * / 运算符";
            }
        }
        log.info("调用完毕");
        return String.valueOf(res);
    }
}