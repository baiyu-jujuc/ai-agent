package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("code")
public class CodeAgent extends AbstractAgent {

    public CodeAgent(ChatClient chatClient) {
        super(chatClient, """
                You are an expert code agent. Your capabilities:
                - Write clean, production-ready code in Java, Python, JavaScript, etc.
                - Debug and fix code issues
                - Explain code concepts clearly
                - Suggest best practices and design patterns
                - Review code for security, performance, and maintainability
                
                Always provide complete, runnable code examples with explanations.
                Use markdown code blocks for code output.
                """);
    }

    @Override
    public String getName() { return "code"; }

    @Override
    public String getDescription() { return "Code generation, debugging, and technical assistance"; }
}
