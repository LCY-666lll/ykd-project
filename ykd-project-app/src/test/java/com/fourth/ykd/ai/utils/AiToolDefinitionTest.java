package com.fourth.ykd.ai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.support.ToolDefinitions;

/** 验证提供给模型的工具名称和参数约束保持稳定。 */
class AiToolDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposeStableUniqueToolNames() throws Exception {
        List<ToolDefinition> definitions = List.of(
                definition(MathCalculatorTool.class, "calculate", String.class),
                definition(TimeTool.class, "getTimeInfo", String.class, String.class),
                definition(BaiduSearchTool.class, "search", String.class, Integer.class),
                definition(WeatherTool.class, "queryCurrentWeather", String.class),
                definition(WeatherTool.class, "queryWeatherForecast", String.class),
                definition(TranslationTool.class, "translation", String.class, String.class, String.class)
        );

        List<String> names = definitions.stream().map(ToolDefinition::name).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "calculate_math_expression",
                "get_time_info",
                "search_realtime_information",
                "query_current_weather",
                "query_weather_forecast",
                "translate_text"
        );
        assertThat(Set.copyOf(names)).hasSameSizeAs(names);
    }

    @Test
    void shouldKeepConditionalAndOptionalParametersOutOfRequiredSchema() throws Exception {
        ToolDefinition timeDefinition = definition(
                TimeTool.class, "getTimeInfo", String.class, String.class);
        ToolDefinition searchDefinition = definition(
                BaiduSearchTool.class, "search", String.class, Integer.class);

        assertThat(requiredParameters(timeDefinition)).containsExactly("operateType");
        assertThat(requiredParameters(searchDefinition)).containsExactly("query");
    }

    @Test
    void shouldDescribeCurrentAndForecastWeatherToolsClearly() throws Exception {
        ToolDefinition weatherDefinition = definition(
                WeatherTool.class, "queryCurrentWeather", String.class);

        ToolDefinition forecastDefinition = definition(
                WeatherTool.class, "queryWeatherForecast", String.class);
        assertThat(weatherDefinition.description())
                .contains("当前实时天气", "必须重新调用", "query_weather_forecast");
        assertThat(forecastDefinition.description())
                .contains("今天、明天和后天", "必须重新调用", "query_current_weather");
    }
    private ToolDefinition definition(Class<?> toolClass, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = toolClass.getDeclaredMethod(methodName, parameterTypes);
        return ToolDefinitions.from(method);
    }

    private List<String> requiredParameters(ToolDefinition definition) throws Exception {
        JsonNode required = objectMapper.readTree(definition.inputSchema()).path("required");
        return StreamSupport.stream(required.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toList());
    }
}