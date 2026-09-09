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
class JwtRouteSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void kbSpacesWithoutJwtReturns403() throws Exception {
        // P3-3: /api/kb/** requires authentication; without JWT -> 403
        mockMvc.perform(get("/api/kb/spaces")
                        .header("X-API-Key", "dev-key-change-in-production"))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatSimpleWithoutJwtReturns403() throws Exception {
        // P3-3: /api/chat/** requires authentication (except public GET endpoints)
        mockMvc.perform(post("/api/chat/simple")
                        .contentType("application/json")
                        .header("X-API-Key", "dev-key-change-in-production")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicGetModelsAllowedWithoutJwt() throws Exception {
        // P3-3: public GET endpoints remain accessible
        mockMvc.perform(get("/api/chat/models"))
                .andExpect(status().isOk());
    }

    @Test
    void authRegisterIsPublic() throws Exception {
        // P3-3: /api/auth/** is public
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"testuser\",\"password\":\"test123456\",\"email\":\"t@t.com\"}"))
                .andExpect(status().isOk());
    }
}
