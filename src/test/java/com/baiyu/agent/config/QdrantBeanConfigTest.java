package com.baiyu.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

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
class QdrantBeanConfigTest {

    @org.springframework.beans.factory.annotation.Autowired
    private ApplicationContext context;

    @Test
    void memoryModeDoesNotCreateQdrantClient() {
        // In memory mode, QdrantClient bean should not exist
        assertThrows(NoSuchBeanDefinitionException.class, () ->
                context.getBean(io.qdrant.client.QdrantClient.class));
    }

    @Test
    void memoryModeCreatesInMemoryVectorStore() {
        // In memory mode, VectorStore should be the InMemory implementation
        Object vs = context.getBean(org.springframework.ai.vectorstore.VectorStore.class);
        assertInstanceOf(VectorStoreConfig.InMemoryVectorStore.class, vs);
    }
}
