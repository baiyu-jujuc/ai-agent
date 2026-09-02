package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.ToolComponent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WeatherTool implements ToolComponent {

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "weather", description = "查询指定城市的天气信息。输入城市名称（中文或英文），例如：北京、Shanghai、Tokyo")
    public String execute(String input) {
        String city = input.trim();
        if (city.isEmpty()) {
            return "请提供城市名称";
        }
        try {
            String response = restClient.get()
                    .uri("https://wttr.in/" + city + "?format=j1")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode current = root.path("current_condition").get(0);

            String tempC = current.path("temp_C").asText();
            String feelsLike = current.path("FeelsLikeC").asText();
            String humidity = current.path("humidity").asText();
            String desc = current.path("weatherDesc").get(0).path("value").asText();
            String windSpeed = current.path("windspeedKmph").asText();
            String area = root.path("nearest_area").get(0).path("areaName").get(0).path("value").asText();

            return String.format(
                    "%s天气: %s, 温度: %s°C (体感 %s°C), 湿度: %s%%, 风速: %s km/h",
                    area, desc, tempC, feelsLike, humidity, windSpeed
            );
        } catch (Exception e) {
            return "查询天气失败: " + e.getMessage();
        }
    }
}
