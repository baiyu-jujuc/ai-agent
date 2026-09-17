package com.baiyu.agent.gateway;

/**
 * 调用场景。它是成本与延迟分析的分组维度，所以用枚举而不是字符串：
 * 拼错一个字符串不会有编译错误，但枚举写错会直接编译失败。
 */
public enum CallScene {

    /** 知识库 RAG 问答（主链路） */
    KB_QA,

    /** /api/chat/simple 普通对话 */
    CHAT_SIMPLE,

    /** /api/chat/stream SSE 流式对话 */
    CHAT_STREAM,

    /** Agent 执行（所有 Agent 子类的公共执行体） */
    AGENT_EXECUTE,

    /** 协调器选 Agent 的路由决策——"隐藏成本"就藏在这里 */
    AGENT_ROUTING,

    /** 工具调用（Function Calling） */
    FUNCTION_CALLING,

    /** legacy RAG（/api/rag/**，默认关闭） */
    LEGACY_RAG,

    /** 离线评测里的模型打分（L4） */
    EVAL_JUDGE
}
