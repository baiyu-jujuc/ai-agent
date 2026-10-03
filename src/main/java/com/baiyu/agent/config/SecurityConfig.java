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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
// 开启方法级安全：管理接口除了"登录了"，还要求"是管理员"。
// 只靠路径级 authenticated() 的话，任何注册用户都能看到成本数据、改模型路由和 Prompt。
@EnableMethodSecurity
public class SecurityConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${agent.security.api-key:dev-key-change-in-production}")
    private String apiKey;

    @Value("${agent.security.protected-paths:/api/**}")
    private String protectedPaths;

    @Value("${agent.security.public-paths:/api/chat/models,/api/chat/tools,/api/chat/storage-status,/api/auth/**}")
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
                // 未认证 → 401、已认证但角色不足 → 403。
                // 默认行为是两者都返回 403，而前端已经区分"401 去登录 / 403 无权限"，
                // 且 ApiKeyInterceptor 对缺失 API Key 也是 401，口径保持一致。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "未认证: 请先登录获取 JWT", 401))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "无权限: 当前账号角色不足", 403)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        // 前端是单文件页面（static/index.html），只放行 UI 真正需要的静态路径。
                        // 这是"默认拒绝"的代价，也是有意的：以后新增静态资源要显式加进来。
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/error").permitAll()
                        // Actuator 收敛：只有健康检查公开（Docker healthcheck 依赖它）。
                        // metrics / prometheus 里有 token、成本、调用量与错误分布，属于"算账数据"，
                        // 因此不只是"要登录"，而是要求 ADMIN 角色——普通用户登录也读不到。
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // 只读的模型/工具清单可以匿名看；/api/agent/** 不再匿名放行——
                        // 一旦这里留着 permitAll，后面新增 POST /api/agent/{name} 就等于对匿名开放（烧 Token、可触发内置工具）
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/chat/models", "/api/chat/tools", "/api/chat/strategies", "/api/chat/storage-status")
                        .permitAll()
                        .requestMatchers("/api/agent/**").authenticated()
                        .requestMatchers("/api/kb/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/api/rag/**").authenticated()
                        .requestMatchers("/api/tools/**").authenticated()
                        // 管理接口（用量、路由、Prompt、评测）：必须带 JWT，
                        // 因为它们能看到成本数据、能改路由和 Prompt
                        .requestMatchers("/api/admin/**").authenticated()
                        // 默认拒绝：新加的接口如果不显式放行，匿名会拿到 401、已登录普通用户会拿到 403。
                        // 这条规则是为了避免"新写一个接口就默认裸奔"。
                        .anyRequest().denyAll()
                )
                .addFilterBefore(new JwtAuthFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message, int code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":" + code + "}");
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
