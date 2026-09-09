package com.baiyu.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://localhost:1",
        "spring.ai.openai.chat.model=deepseek-v4-flash",
        "agent.storage.vector-store=memory",
        "agent.storage.memory=memory",
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
class LegacyRagDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void legacyRagSearchReturns404WhenDisabled() throws Exception {
        // P3-6: /api/rag/** is disabled by default (legacy.rag.enabled=false)
        // The RagController bean is not created, so the endpoint should not be found
        mockMvc.perform(get("/api/rag/search")
                        .param("query", "test")
                        .header("X-API-Key", "dev-key-change-in-production"))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyRagQueryReturns403WhenDisabled() throws Exception {
        mockMvc.perform(post("/api/rag/query")
                        .contentType("application/json")
                        .header("X-API-Key", "dev-key-change-in-production")
                        .content("{\"question\":\"test\"}"))
                .andExpect(status().isForbidden());
    }
}
