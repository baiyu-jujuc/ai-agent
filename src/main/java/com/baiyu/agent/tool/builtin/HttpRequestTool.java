package com.baiyu.agent.tool.builtin;

import com.baiyu.agent.tool.ToolComponent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;

@Component
public class HttpRequestTool implements ToolComponent {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "metadata.google.internal",
            "169.254.169.254", "metadata.aws.internal"
    );

    private static final String[] BLOCKED_IP_PREFIXES = {
            "127.", "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "169.254.", "0.0.0.0",
            "::1", "fe80:", "fc00:", "fd00:"
    };

    private final RestClient restClient;

    public HttpRequestTool() {
        this.restClient = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(15).toMillis());
                }})
                .build();
    }

    @Tool(name = "http", description = "发送 HTTP GET 请求（已加 SSRF 防护，禁止访问内网/元数据地址）。输入一个 URL，返回响应内容（截断到 3000 字符）")
    public String execute(String input) {
        String url = input.trim();
        if (url.isEmpty()) {
            return "请提供 URL";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        String blockedReason = checkSsrf(url);
        if (blockedReason != null) {
            return "安全拒绝: " + blockedReason;
        }

        try {
            String response = restClient.get()
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

    private String checkSsrf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return "无效的 URL: 缺少 host";
            }

            String hostLower = host.toLowerCase();
            if (BLOCKED_HOSTS.contains(hostLower)) {
                return "禁止访问: " + host + " (内网/元数据地址)";
            }

            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                String ip = addr.getHostAddress();
                for (String prefix : BLOCKED_IP_PREFIXES) {
                    if (ip.startsWith(prefix)) {
                        return "禁止访问: " + host + " 解析到内网地址 " + ip;
                    }
                }
            }
            return null;
        } catch (UnknownHostException e) {
            return "域名无法解析: " + url;
        } catch (Exception e) {
            return "URL 校验失败: " + e.getMessage();
        }
    }
}
