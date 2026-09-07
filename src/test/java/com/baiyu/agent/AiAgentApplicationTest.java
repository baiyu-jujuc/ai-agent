package com.baiyu.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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
        "agent.security.api-key=test-key",
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class AiAgentApplicationTest {

    @Test
    void contextLoads() {
    }
}
