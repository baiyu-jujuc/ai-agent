package com.baiyu.agent.agent;

import com.baiyu.agent.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReActAgent extends AbstractAgent {

    private static final int MAX_ITERATIONS = 5;
    private final ToolRegistry toolRegistry;

    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(\\w+)\\s*\\n\\s*Input:\\s*(.+)", Pattern.CASE_INSENSITIVE);

    public ReActAgent(ChatClient chatClient, ToolRegistry toolRegistry) {
        super(chatClient, """
                You are a ReAct (Reasoning + Acting) agent. Follow this loop:
                
                1. Thought: Think about what to do
                2. Action: Choose a tool to use (or 'final' to give the final answer)
                3. Input: The input for the tool
                
                Available tools:
                - calculator: Evaluate math expressions
                - time: Get current time
                - systeminfo: Get system information
                - final: Give the final answer (no tool needed)
                
                Format your response EXACTLY as:
                Thought: <your reasoning>
                Action: <tool_name>
                Input: <tool_input>
                
                Or when you have the final answer:
                Thought: <your reasoning>
                Action: final
                Input: <your final answer>
                """);
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String getName() { return "react"; }

    @Override
    public String getDescription() { return "ReAct agent with reasoning-action loop"; }

    @Override
    public String execute(String input, List<Message> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        if (context != null && !context.isEmpty()) {
            messages.addAll(context);
        }

        String currentInput = input;
        StringBuilder trace = new StringBuilder();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            messages.add(new UserMessage(currentInput));

            String response = chatClient.prompt(new Prompt(messages))
                    .call()
                    .content();

            trace.append("--- Iteration ").append(i + 1).append(" ---\n");
            trace.append(response).append("\n");

            Matcher matcher = ACTION_PATTERN.matcher(response);
            if (matcher.find()) {
                String action = matcher.group(1).trim().toLowerCase();
                String toolInput = matcher.group(2).trim();

                if ("final".equals(action)) {
                    return toolInput;
                }

                String toolResult = toolRegistry.executeTool(action, toolInput);
                trace.append("Tool Result: ").append(toolResult).append("\n\n");

                messages.add(new org.springframework.ai.chat.messages.AssistantMessage(response));
                currentInput = "Observation: " + toolResult;
            } else {
                return response;
            }
        }

        return "Max iterations reached.\n\nTrace:\n" + trace;
    }
}
