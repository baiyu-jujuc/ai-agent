package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CoordinatorAgent extends AbstractAgent {

    private final Map<String, Agent> agents;

    public CoordinatorAgent(ChatClient chatClient, Map<String, Agent> agents) {
        super(chatClient, """
                You are a coordinator agent. Your job is to:
                1. Analyze the user's request
                2. Determine which specialist agent should handle it
                3. Route the request to the appropriate agent
                
                Available agents and their capabilities:
                - code: Code generation, debugging, and technical questions
                - research: Information retrieval, web search, and knowledge synthesis
                - data: Data analysis, database queries, and report generation
                
                Respond with structured, comprehensive answers.
                """);
        this.agents = agents;
    }

    @Override
    public String getName() {
        return "coordinator";
    }

    @Override
    public String getDescription() {
        return "Routes requests to specialist agents";
    }

    @Override
    public String execute(String input, List<Message> context) {
        String routingDecision = determineAgent(input);
        Agent targetAgent = agents.getOrDefault(routingDecision, this);
        
        if (targetAgent == this) {
            return super.execute(input, context);
        }
        
        return targetAgent.execute(input, context);
    }

    private String determineAgent(String input) {
        String lower = input.toLowerCase();
        if (lower.contains("code") || lower.contains("debug") || lower.contains("function") 
                || lower.contains("class") || lower.contains("java") || lower.contains("program")) {
            return "code";
        }
        if (lower.contains("search") || lower.contains("research") || lower.contains("find")
                || lower.contains("what is") || lower.contains("explain")) {
            return "research";
        }
        if (lower.contains("data") || lower.contains("query") || lower.contains("analyze")
                || lower.contains("report") || lower.contains("statistics")) {
            return "data";
        }
        return "coordinator";
    }
}
