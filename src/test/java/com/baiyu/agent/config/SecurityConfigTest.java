package com.baiyu.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// B5: Tests must NOT make real network calls. Use localhost:1 for instant connection refusal.
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://localhost:1",
        "spring.ai.openai.chat.model=deepseek-v4-flash",
        "spring.ai.retry.max-attempts=1",
        "spring.ai.retry.backoff.initial-interval=0",
        "spring.ai.retry.backoff.multiplier=1",
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
        mockMvc.perform(post("/api/chat/simple")
                        .contentType("application/json")
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPostWithCorrectKey() throws Exception {
        mockMvc.perform(post("/api/chat/simple")
                        .contentType("application/json")
                        .header("X-API-Key", "test-secret-key")
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedPostWithWrongKey() throws Exception {
        mockMvc.perform(post("/api/chat/simple")
                        .contentType("application/json")
                        .header("X-API-Key", "wrong-key")
                        .content("{\"message\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentHealthIsPublic() throws Exception {
        mockMvc.perform(get("/api/agent/health"))
                .andExpect(status().isOk());
    }
}
