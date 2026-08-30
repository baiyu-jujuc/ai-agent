package com.baiyu.agent.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FunctionCallingService {

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;

    public FunctionCallingService(ChatClient chatClient, ToolRegistry toolRegistry) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
    }

    public String executeWithTools(String userInput, String model) {
        String toolList = toolRegistry.getAllTools().stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .reduce("", (a, b) -> a + b + "\n");

        String prompt = userInput + "\n\n你可以使用以下工具来帮助回答:\n" + toolList +
                "\n如果需要使用工具，请用 JSON 格式回复: " +
                "{\"tool\": \"工具名\", \"input\": \"参数\"}\n" +
                "如果不需要工具，直接回复用户问题。";

        String response;
        if (model != null && !model.isEmpty()) {
            response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage("你是多功能AI助手，可以调用工具。"),
                    new UserMessage(prompt)
            ))).options(ChatOptions.builder().model(model).build()).call().content();
        } else {
            response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage("你是多功能AI助手，可以调用工具。"),
                    new UserMessage(prompt)
            ))).call().content();
        }

        if (response != null && response.contains("\"tool\"")) {
            return tryExecuteTool(response);
        }
        return response;
    }

    private String tryExecuteTool(String response) {
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = response.substring(start, end + 1);
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(json);
                String toolName = node.path("tool").asText();
                String toolInput = node.path("input").asText();

                if (!toolName.isEmpty()) {
                    String result = toolRegistry.executeTool(toolName, toolInput);
                    String followUp = chatClient.prompt()
                            .system("你是一个AI助手，你调用了工具并获得了结果，请根据结果用自然语言回答用户。")
                            .user("工具调用结果: " + result + "\n请用自然语言总结这个结果。")
                            .call()
                            .content();
                    return followUp != null ? followUp : result;
                }
            }
        } catch (Exception e) {
            return response;
        }
        return response;
    }
}
