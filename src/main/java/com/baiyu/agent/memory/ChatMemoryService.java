package com.baiyu.agent.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatMemoryService {

    private static final int MAX_HISTORY = 20;
    private final Map<String, LinkedList<Message>> conversations = new ConcurrentHashMap<>();

    public void addUserMessage(String conversationId, String content) {
        addToHistory(conversationId, new UserMessage(content));
    }

    public void addAssistantMessage(String conversationId, String content) {
        addToHistory(conversationId, new AssistantMessage(content));
    }

    public List<Message> getHistory(String conversationId) {
        LinkedList<Message> history = conversations.get(conversationId);
        if (history == null) return Collections.emptyList();
        return new ArrayList<>(history);
    }

    public void clearHistory(String conversationId) {
        conversations.remove(conversationId);
    }

    public Set<String> getConversationIds() {
        return conversations.keySet();
    }

    private void addToHistory(String conversationId, Message message) {
        conversations.computeIfAbsent(conversationId, k -> new LinkedList<>()).add(message);
        LinkedList<Message> history = conversations.get(conversationId);
        while (history.size() > MAX_HISTORY * 2) {
            history.pollFirst();
        }
    }
}
