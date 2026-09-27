package com.baiyu.agent.config;

import com.baiyu.agent.user.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越权修复的回归测试（对应执行文档之外的加固项）：
 *
 * <ol>
 *   <li>`/api/admin/**` 不再是"登录即可"——普通用户拿到 JWT 也只能得 403，必须 ADMIN 角色；</li>
 *   <li>`/actuator/**` 收敛：只有 `/actuator/health` 公开，
 *       `metrics` / `prometheus` 必须带 JWT（它们是运行数据：内存、线程、调用量都能从里面读出来）。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://localhost:1",
        "spring.ai.openai.chat.model=deepseek-v4-flash",
        "agent.storage.vector-store=memory",
        "agent.storage.memory=memory",
        "agent.security.api-key=test-secret-key",
        "agent.security.rate-limit-per-minute=200",
        // 测试上下文不会加载主 application.yml（测试资源里有一份同名的），所以这里显式声明暴露的端点
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
class AdminAccessSecurityTest {

    private static final String API_KEY = "test-secret-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    private String tokenFor(String role) {
        return jwtUtil.generateToken("user-" + role, role + "-tester", role);
    }

    // ---------------------------------------------------------------- /api/admin/**

    @Test
    void adminEndpointRejectsRequestWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/admin/usage").header("X-API-Key", API_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointRejectsNormalUser() throws Exception {
        mockMvc.perform(get("/api/admin/usage")
                        .header("X-API-Key", API_KEY)
                        .header("Authorization", "Bearer " + tokenFor("user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointAllowsAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/usage")
                        .header("X-API-Key", API_KEY)
                        .header("Authorization", "Bearer " + tokenFor("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void adminModelsRejectsNormalUser() throws Exception {
        mockMvc.perform(get("/api/admin/models")
                        .header("X-API-Key", API_KEY)
                        .header("Authorization", "Bearer " + tokenFor("user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminModelsAllowsAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/models")
                        .header("X-API-Key", API_KEY)
                        .header("Authorization", "Bearer " + tokenFor("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void adminPromptsRejectsNormalUser() throws Exception {
        mockMvc.perform(get("/api/admin/prompts")
                        .header("X-API-Key", API_KEY)
                        .header("Authorization", "Bearer " + tokenFor("user")))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- /actuator/**

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorMetricsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorPrometheusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorMetricsIsAdminOnly() throws Exception {
        // 指标端点是"算账数据"（token 总量、成本、调用量、错误分布），
        // 所以普通用户即使登录也不能读，必须 ADMIN。
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + tokenFor("user")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + tokenFor("admin")))
                .andExpect(status().isOk());
    }
}
