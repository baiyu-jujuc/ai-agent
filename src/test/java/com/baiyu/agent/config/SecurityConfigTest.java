package com.baiyu.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// B5: Tests must NOT make real LLM calls. Protected POST is verified with the calculator tool.
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://localhost:1",
        "spring.ai.openai.chat.model=deepseek-v4-flash",
        "agent.storage.vector-store=memory",
        "agent.storage.memory=memory",
        "agent.security.api-key=test-secret-key",
        "agent.security.rate-limit-per-minute=100",
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicGetWithoutKeyAllowed() throws Exception {
        mockMvc.perform(get("/api/chat/models"))
                .andExpect(status().isOk());
    }

    @Test
    void postWithoutJwtReturns401() throws Exception {
        // 未认证统一返回 401（不是 403）：前端按 401 跳登录、按 403 提示无权限
        mockMvc.perform(post("/api/tools/calculator")
                        .contentType("application/json")
                        .content("{\"input\":\"2+3\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithApiKeyButNoJwtReturns401() throws Exception {
        // 只有 API Key 不算认证：平台 Key 只用于识别客户端，JWT 才是"谁在调用"
        mockMvc.perform(post("/api/tools/calculator")
                        .contentType("application/json")
                        .header("X-API-Key", "test-secret-key")
                        .content("{\"input\":\"2+3\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithWrongApiKeyAndNoJwtReturns401() throws Exception {
        // API Key 对不对都不影响结论：没有 JWT 就是未认证
        mockMvc.perform(post("/api/tools/calculator")
                        .contentType("application/json")
                        .header("X-API-Key", "wrong-key")
                        .content("{\"input\":\"2+3\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentEndpointsRequireAuth() throws Exception {
        // /api/agent/** 不再匿名放行：否则后面新增 POST /api/agent/{name} 就等于对匿名开放
        mockMvc.perform(get("/api/agent/health"))
                .andExpect(status().isUnauthorized());
    }
}
