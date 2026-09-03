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
                你是一个协调智能体（Coordinator Agent）。你的职责：
                1. 分析用户请求
                2. 判断应由哪个专家 Agent 处理
                3. 将请求路由到合适的 Agent
                
                可用的 Agent 及其能力：
                - code: 代码生成、调试、报错分析、编程技术问题
                - research: 查资料、解释概念、知识问答、信息检索与综述
                - data: 数据分析、SQL/数据库、统计、报表
                
                用中文回答。
                """);
        this.agents = agents;
    }

    @Override
    public String getName() { return "coordinator"; }

    @Override
    public String getDescription() { return "路由请求到专家 Agent"; }

    @Override
    public String execute(String input, List<Message> context) {
        return executeWithModel(input, null, context);
    }

    @Override
    public String executeWithModel(String input, String model, List<Message> context) {
        String routingDecision = determineAgent(input);
        Agent targetAgent = agents.getOrDefault(routingDecision, this);

        if (targetAgent == this) {
            return super.executeWithModel(input, model, context);
        }

        log.info("Routing to agent: {} for input: {}", routingDecision,
                input.length() > 50 ? input.substring(0, 50) + "..." : input);
        return targetAgent.executeWithModel(input, model, context);
    }

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
                if (agents.containsKey(name) || "coordinator".equals(name)) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.warn("LLM routing failed, falling back to heuristic: {}", e.getMessage());
        }
        return heuristicRoute(input);
    }

    private String heuristicRoute(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.contains("code") || lower.contains("debug") || lower.contains("function")
                || lower.contains("class") || lower.contains("java") || lower.contains("program")
                || lower.contains("代码") || lower.contains("调试") || lower.contains("报错")
                || lower.contains("程序") || lower.contains("函数") || lower.contains("编程")) {
            return "code";
        }
        if (lower.contains("data") || lower.contains("query") || lower.contains("analyze")
                || lower.contains("report") || lower.contains("statistics") || lower.contains("sql")
                || lower.contains("数据") || lower.contains("统计") || lower.contains("报表")
                || lower.contains("分析")) {
            return "data";
        }
        if (lower.contains("search") || lower.contains("research") || lower.contains("find")
                || lower.contains("what is") || lower.contains("explain")
                || lower.contains("搜索") || lower.contains("查") || lower.contains("解释")
                || lower.contains("是什么") || lower.contains("为什么")) {
            return "research";
        }
        return "coordinator";
    }

    public record RoutingDecision(String agent) {}
}
