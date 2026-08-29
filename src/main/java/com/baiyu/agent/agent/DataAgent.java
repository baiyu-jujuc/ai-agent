package com.baiyu.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component("data")
public class DataAgent extends AbstractAgent {

    public DataAgent(ChatClient chatClient) {
        super(chatClient, """
                You are a data analysis agent. Your capabilities:
                - Analyze data and provide insights
                - Generate SQL queries
                - Create data summaries and reports
                - Suggest data visualization approaches
                - Identify trends, patterns, and anomalies
                
                Always explain your analysis step by step.
                Format data in tables when appropriate.
                """);
    }

    @Override
    public String getName() { return "data"; }

    @Override
    public String getDescription() { return "Data analysis, database queries, and report generation"; }
}
