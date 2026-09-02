package com.baiyu.agent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * API Key 鉴权。保护 /api/** 全部接口（含 /api/chat/**、/api/rag/**），
 * 仅放行 public-paths 中的只读查询接口。
 * 前端需在请求头携带 X-API-Key。
 */
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    @Value("${agent.security.api-key:dev-key-change-in-production}")
    private String apiKey;

    @Value("${agent.security.protected-paths:/api/**}")
    private String protectedPaths;

    @Value("${agent.security.public-paths:/api/chat/models,/api/chat/tools,/api/chat/storage-status,/api/agent/**}")
    private String publicPaths;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        List<String> excludes = Arrays.stream(publicPaths.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        registry.addInterceptor(new ApiKeyInterceptor(apiKey, excludes))
                .addPathPatterns(protectedPaths.split(","))
                .excludePathPatterns(excludes);
    }

    private static class ApiKeyInterceptor implements HandlerInterceptor {

        private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

        private final byte[] expectedKeyBytes;
        private final List<String> publicPatterns;

        ApiKeyInterceptor(String expectedApiKey, List<String> publicPatterns) {
            this.expectedKeyBytes = expectedApiKey.getBytes(StandardCharsets.UTF_8);
            this.publicPatterns = publicPatterns;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String uri = request.getRequestURI();
            boolean isPublic = "GET".equalsIgnoreCase(request.getMethod())
                    && publicPatterns.stream().anyMatch(p -> PATH_MATCHER.match(p, uri));
            if (isPublic) {
                return true;
            }

            // 仅接受请求头传递密钥，避免密钥落入 URL / 访问日志
            String providedKey = request.getHeader("X-API-Key");
            if (providedKey == null || providedKey.isEmpty() || !constantTimeEquals(providedKey)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"未授权: 请在请求头提供有效的 X-API-Key\",\"code\":401}");
                return false;
            }
            return true;
        }

        private boolean constantTimeEquals(String provided) {
            byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedKeyBytes, providedBytes);
        }
    }
}
