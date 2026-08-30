package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Component
public class WebSearchTool implements Tool {

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "web-search";
    }

    @Override
    public String getDescription() {
        return "搜索网络信息。输入搜索关键词，返回相关结果摘要";
    }

    @Override
    public String execute(String input) {
        String query = input.trim();
        if (query.isEmpty()) {
            return "请提供搜索关键词";
        }
        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("api.duckduckgo.com")
                            .path("/")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("no_html", "1")
                            .queryParam("skip_disambig", "1")
                            .build())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            StringBuilder result = new StringBuilder();

            String abstractText = root.path("AbstractText").asText();
            String abstractSource = root.path("AbstractSource").asText();
            String abstractUrl = root.path("AbstractURL").asText();

            if (!abstractText.isEmpty()) {
                result.append("摘要: ").append(abstractText).append("\n");
                if (!abstractSource.isEmpty()) {
                    result.append("来源: ").append(abstractSource).append("\n");
                }
                if (!abstractUrl.isEmpty()) {
                    result.append("链接: ").append(abstractUrl).append("\n");
                }
            }

            JsonNode related = root.path("RelatedTopics");
            List<String> relatedList = new ArrayList<>();
            for (JsonNode topic : related) {
                String text = topic.path("Text").asText();
                if (!text.isEmpty()) {
                    relatedList.add(text);
                    if (relatedList.size() >= 5) break;
                }
            }

            if (!relatedList.isEmpty()) {
                result.append("\n相关结果:\n");
                for (int i = 0; i < relatedList.size(); i++) {
                    result.append(i + 1).append(". ").append(relatedList.get(i)).append("\n");
                }
            }

            if (result.isEmpty()) {
                return "未找到关于 \"" + query + "\" 的相关信息";
            }
            return result.toString();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }
}
