package com.baiyu.agent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${agent.security.api-key:dev-key-change-in-production}")
    private String apiKey;

    @Value("${agent.security.protected-paths:/api/tools/**}")
    private String protectedPaths;

    @Value("${agent.security.rate-limit-per-minute:30}")
    private int rateLimitPerMinute;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        for (String path : protectedPaths.split(",")) {
            registry.addInterceptor(new ApiKeyInterceptor(apiKey))
                    .addPathPatterns(path.trim());
        }
        registry.addInterceptor(new RateLimitInterceptor(rateLimitPerMinute))
                .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    private static class ApiKeyInterceptor implements HandlerInterceptor {
        private final String expectedApiKey;

        ApiKeyInterceptor(String expectedApiKey) {
            this.expectedApiKey = expectedApiKey;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            String providedKey = request.getHeader("X-API-Key");
            if (providedKey == null || !providedKey.equals(expectedApiKey)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"未授权: 请提供有效的 X-API-Key\",\"code\":401}");
                return false;
            }
            return true;
        }
    }

    private static class RateLimitInterceptor implements HandlerInterceptor {
        private final int maxRequestsPerMinute;
        private final Map<String, RateBucket> buckets = new ConcurrentHashMap<>();

        RateLimitInterceptor(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            String clientId = request.getRemoteAddr();
            RateBucket bucket = buckets.computeIfAbsent(clientId, k -> new RateBucket(maxRequestsPerMinute));

            if (!bucket.tryAcquire()) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"请求过于频繁，请稍后重试\",\"code\":429}");
                return false;
            }
            return true;
        }
    }

    private static class RateBucket {
        private final int maxRequests;
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        RateBucket(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart > TimeUnit.MINUTES.toMillis(1)) {
                synchronized (this) {
                    if (now - windowStart > TimeUnit.MINUTES.toMillis(1)) {
                        windowStart = now;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet() <= maxRequests;
        }
    }
}
