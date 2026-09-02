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

    private static final Set<String> BLOCKED_HOST_SUFFIXES = Set.of(
            ".local", ".internal", ".localhost", ".invalid"
    );

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
            if (BLOCKED_HOSTS.contains(hostLower)
                    || BLOCKED_HOST_SUFFIXES.stream().anyMatch(hostLower::endsWith)) {
                return "禁止访问: " + host + " (内网/元数据地址)";
            }

            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                String reason = checkAddress(addr);
                if (reason != null) {
                    return "禁止访问: " + host + " 解析到受限地址 " + addr.getHostAddress() + " (" + reason + ")";
                }
            }

            return null;
        } catch (UnknownHostException e) {
            return "域名无法解析: " + url;
        } catch (Exception e) {
            return "URL 校验失败: " + e.getMessage();
        }
    }

    private String checkAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()) return "通配地址";
        if (addr.isLoopbackAddress()) return "环回地址";
        if (addr.isLinkLocalAddress()) return "链路本地地址(含云元数据 169.254/16)";
        if (addr.isSiteLocalAddress()) return "内网地址";
        if (addr.isMulticastAddress()) return "组播地址";

        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                return "CGNAT 运营商内网地址";
            }
            if (first == 198 && (second == 18 || second == 19)) return "保留网段";
            if (first == 0) return "保留网段";
            if (first == 255 && second == 255 && (bytes[2] & 0xFF) == 255 && (bytes[3] & 0xFF) == 255) return "广播地址";
        } else if (bytes.length == 16) {
            if ((bytes[0] & 0xFE) == 0xFC) return "IPv6 ULA 内网地址";
        }
        return null;
    }
}
