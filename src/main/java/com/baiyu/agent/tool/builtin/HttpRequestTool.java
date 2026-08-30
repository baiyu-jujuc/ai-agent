package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class HttpRequestTool implements Tool {

    @Override
    public String getName() {
        return "http";
    }

    @Override
    public String getDescription() {
        return "发送 HTTP GET 请求。输入一个 URL，返回响应内容（截断到 3000 字符）";
    }

    @Override
    public String execute(String input) {
        String url = input.trim();
        if (url.isEmpty()) {
            return "请提供 URL";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        try {
            RestClient client = RestClient.builder()
                    .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                        setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                        setReadTimeout((int) Duration.ofSeconds(15).toMillis());
                    }})
                    .build();

            String response = client.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (response == null) {
                return "响应为空";
            }
            if (response.length() > 3000) {
                return response.substring(0, 3000) + "\n... (截断，共 " + response.length() + " 字符)";
            }
            return response;
        } catch (Exception e) {
            return "HTTP 请求失败: " + e.getMessage();
        }
    }
}
