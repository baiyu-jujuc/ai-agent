package com.baiyu.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final int MAX_HISTORY = 20;
    private static final String REDIS_KEY_PREFIX = "chat:memory:";
    private static final long REDIS_TTL_HOURS = 24;

    @Value("${agent.storage.memory:memory}")
    private String memoryType;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, LinkedList<Message>> conversations = new ConcurrentHashMap<>();

    public ChatMemoryService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void addUserMessage(String conversationId, String content) {
        addToHistory(conversationId, new UserMessage(content));
    }

    public void addAssistantMessage(String conversationId, String content) {
        addToHistory(conversationId, new AssistantMessage(content));
    }

    public List<Message> getHistory(String conversationId) {
        if (useRedis()) {
            return getHistoryFromRedis(conversationId);
        }
        LinkedList<Message> history = conversations.get(conversationId);
        if (history == null) return Collections.emptyList();
        return new ArrayList<>(history);
    }

    public void clearHistory(String conversationId) {
        if (useRedis()) {
            try {
                redisTemplate.delete(REDIS_KEY_PREFIX + conversationId);
            } catch (Exception e) {
                log.warn("Redis clear failed, falling back to in-memory: {}", e.getMessage());
            }
            return;
        }
        conversations.remove(conversationId);
    }

    public Set<String> getConversationIds() {
        if (useRedis()) {
            try {
                Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
                if (keys == null || keys.isEmpty()) return Collections.emptySet();
                Set<String> ids = new HashSet<>();
                for (String key : keys) {
                    ids.add(key.substring(REDIS_KEY_PREFIX.length()));
                }
                return ids;
            } catch (Exception e) {
                log.warn("Redis keys scan failed, falling back to in-memory: {}", e.getMessage());
                return conversations.keySet();
            }
        }
        return conversations.keySet();
    }

    public String getStorageType() {
        return useRedis() ? "redis" : "memory";
    }

    private boolean useRedis() {
        return "redis".equals(memoryType) && redisTemplate != null;
    }

    private void addToHistory(String conversationId, Message message) {
        if (useRedis()) {
            addToRedisHistory(conversationId, message);
            return;
        }
        conversations.computeIfAbsent(conversationId, k -> new LinkedList<>()).add(message);
        LinkedList<Message> history = conversations.get(conversationId);
        while (history.size() > MAX_HISTORY * 2) {
            history.pollFirst();
        }
    }

    private void addToRedisHistory(String conversationId, Message message) {
        String key = REDIS_KEY_PREFIX + conversationId;
        try {
            String json = objectMapper.writeValueAsString(new MessageRecord(
                    message.getMessageType().name(),
                    message.getText()
            ));
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, REDIS_TTL_HOURS, TimeUnit.HOURS);

            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > MAX_HISTORY * 2) {
                redisTemplate.opsForList().trim(key, size - MAX_HISTORY * 2, -1);
            }
        } catch (Exception e) {
            log.warn("Redis write failed, falling back to in-memory: {}", e.getMessage());
            conversations.computeIfAbsent(conversationId, k -> new LinkedList<>()).add(message);
            LinkedList<Message> history = conversations.get(conversationId);
            while (history.size() > MAX_HISTORY * 2) {
                history.pollFirst();
            }
        }
    }

    private List<Message> getHistoryFromRedis(String conversationId) {
        String key = REDIS_KEY_PREFIX + conversationId;
        try {
            List<String> rawList = redisTemplate.opsForList().range(key, 0, -1);
            if (rawList == null || rawList.isEmpty()) return Collections.emptyList();

            List<Message> messages = new ArrayList<>();
            for (String raw : rawList) {
                MessageRecord record = objectMapper.readValue(raw, MessageRecord.class);
                if ("USER".equals(record.role())) {
                    messages.add(new UserMessage(record.content()));
                } else if ("ASSISTANT".equals(record.role())) {
                    messages.add(new AssistantMessage(record.content()));
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("Redis read failed, falling back to in-memory: {}", e.getMessage());
            LinkedList<Message> history = conversations.get(conversationId);
            if (history == null) return Collections.emptyList();
            return new ArrayList<>(history);
        }
    }

    private record MessageRecord(String role, String content) {}
}
