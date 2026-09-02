package com.baiyu.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CoordinatorAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorAgent.class);

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

    /**
     * LLM 意图分类路由：让模型判断请求应交给哪个专家 Agent，
     * 支持任意语言（含中文）。分类失败时降级为关键词启发式路由。
     */
    private String determineAgent(String input) {
        if (input == null || input.isBlank()) {
            return "coordinator";
        }
        try {
            RoutingDecision decision = chatClient.prompt()
                    .system("""
                            你是一个意图分类器。根据用户请求判断应由哪个专家处理，只输出 JSON，不要解释。
                            可选值:
                            - code: 写代码、调试、报错分析、编程技术问题
                            - research: 查资料、解释概念、知识问答、信息检索与综述
                            - data: 数据分析、SQL/数据库、统计、报表
                            - coordinator: 闲聊或其他不属于以上类型的请求
                            """)
                    .user(input)
                    .options(ChatOptions.builder().temperature(0.0).build())
                    .call()
                    .entity(RoutingDecision.class);

            if (decision != null && decision.agent() != null) {
                String name = decision.agent().trim().toLowerCase(Locale.ROOT);
                if (agents.containsKey(name)) {
                    return name;
                }
                if ("coordinator".equals(name)) {
                    return "coordinator";
                }
            }
        } catch (Exception e) {
            log.warn("LLM routing failed, falling back to heuristic: {}", e.getMessage());
        }
        return heuristicRoute(input);
    }

    /** LLM 分类失败时的兜底路由（中英文关键词） */
    private String heuristicRoute(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.contains("code") || lower.contains("debug") || lower.contains("function")
                || lower.contains("class") || lower.contains("java") || lower.contains("program")
                || lower.contains("代码") || lower.contains("调试") || lower.contains("报错")
                || lower.contains("程序") || lower.contains("函数")) {
            return "code";
        }
        if (lower.contains("data") || lower.contains("query") || lower.contains("analyze")
                || lower.contains("report") || lower.contains("statistics") || lower.contains("sql")
                || lower.contains("数据") || lower.contains("统计") || lower.contains("报表")) {
            return "data";
        }
        if (lower.contains("search") || lower.contains("research") || lower.contains("find")
                || lower.contains("what is") || lower.contains("explain")
                || lower.contains("搜索") || lower.contains("查") || lower.contains("解释")
                || lower.contains("是什么")) {
            return "research";
        }
        return "coordinator";
    }

    public record RoutingDecision(String agent) {
    }
}
