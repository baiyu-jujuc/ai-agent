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
    void publicPostWithoutKeyBlocked() throws Exception {
        // P3-3: Spring Security now enforces authentication at route level.
        // Without JWT, /api/tools/** returns 403 (Spring Security blocks before ApiKeyInterceptor)
        mockMvc.perform(post("/api/tools/calculator")
                        .contentType("application/json")
                        .content("{\"input\":\"2+3\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedPostWithCorrectKey() throws Exception {
        // P3-3: Even with correct API Key, /api/tools/** requires JWT authentication.
        // Without JWT, Spring Security blocks the request with 403.
        mockMvc.perform(post("/api/tools/calculator")
                        .contentType("application/json")
                        .header("X-API-Key", "test-secret-key")
                        .content("{\"input\":\"2+3\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedPostWithWrongKey() throws Exception {
        // P3-3: Without JWT, Spring Security blocks regardless of API Key validity.
        mockMvc.perform(post("/api/tools/calculator")
                        .contentType("application/json")
                        .header("X-API-Key", "wrong-key")
                        .content("{\"input\":\"2+3\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentHealthIsPublic() throws Exception {
        mockMvc.perform(get("/api/agent/health"))
                .andExpect(status().isOk());
    }
}
