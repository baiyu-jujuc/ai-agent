package com.baiyu.agent.config;

import com.baiyu.agent.user.JwtAuthFilter;
import com.baiyu.agent.user.JwtUtil;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableWebSecurity
public class SecurityConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${agent.security.api-key:dev-key-change-in-production}")
    private String apiKey;

    @Value("${agent.security.protected-paths:/api/**}")
    private String protectedPaths;

    @Value("${agent.security.public-paths:/api/chat/models,/api/chat/tools,/api/chat/storage-status,/api/agent/**,/api/auth/**}")
    private String publicPaths;

    @Value("${agent.security.rate-limit-per-minute:30}")
    private int rateLimitPerMinute;

    @Value("${agent.security.allowed-origins:http://localhost:8080,http://localhost:3000,http://127.0.0.1:8080}")
    private String allowedOrigins;

    private RateLimitInterceptor rateLimitInterceptor;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @PostConstruct
    public void init() {
        this.rateLimitInterceptor = new RateLimitInterceptor(rateLimitPerMinute);
        log.info("SecurityConfig initialized: protected={}, public(GET only)={}, rateLimit={}/min",
                protectedPaths, publicPaths, rateLimitPerMinute);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtUtil jwtUtil) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/**").permitAll()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(new JwtAuthFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiKeyInterceptor(apiKey, splitCsv(publicPaths)))
                .addPathPatterns(splitCsv(protectedPaths).toArray(String[]::new));

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = splitCsv(allowedOrigins).toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
        log.info("CORS allowed origins: {}", Arrays.toString(origins));
    }

    @Scheduled(fixedRate = 300000)
    public void cleanupRateLimitBuckets() {
        rateLimitInterceptor.cleanup();
    }

    private static List<String> splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true;
            }

            String uri = request.getRequestURI();
            boolean isPublic = "GET".equalsIgnoreCase(request.getMethod())
                    && publicPatterns.stream().anyMatch(p -> PATH_MATCHER.match(p, uri));
            if (isPublic) {
                return true;
            }

            // /api/auth/** is public for POST (register/login)
            if (uri.startsWith("/api/auth/")) {
                return true;
            }

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
            return MessageDigest.isEqual(expectedKeyBytes, provided.getBytes(StandardCharsets.UTF_8));
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
            RateBucket bucket = buckets.computeIfAbsent(request.getRemoteAddr(),
                    k -> new RateBucket(maxRequestsPerMinute));

            if (!bucket.tryAcquire()) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"请求过于频繁，请稍后重试\",\"code\":429}");
                return false;
            }
            return true;
        }

        void cleanup() {
            long now = System.currentTimeMillis();
            long expiry = TimeUnit.MINUTES.toMillis(2);
            int before = buckets.size();
            buckets.entrySet().removeIf(e -> now - e.getValue().windowStart > expiry);
            int removed = before - buckets.size();
            if (removed > 0) {
                log.debug("Rate limiter cleanup: removed {} expired buckets (remaining: {})", removed, buckets.size());
            }
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
