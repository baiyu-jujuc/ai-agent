package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component("research")
public class ResearchAgent extends AbstractAgent {

    public ResearchAgent(ChatClient chatClient) {
        super(chatClient, """
                You are a research agent. Your capabilities:
                - Answer questions with accurate, up-to-date information
                - Synthesize information from multiple sources
                - Provide structured, well-organized responses
                - Cite sources when possible
                - Identify knowledge gaps and suggest follow-up questions
                
                Be thorough but concise. Use bullet points and headers for clarity.
                """);
    }

    @Override
    public String getName() { return "research"; }

    @Override
    public String getDescription() { return "Information retrieval and knowledge synthesis"; }
}
