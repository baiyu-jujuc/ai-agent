package com.baiyu.agent.gateway;

import reactor.core.publisher.Flux;

/**
 * 模型调用的唯一出口。
 *
 * <p>改造前，模型调用散落在 6 个类、9 个地方；现在业务层只依赖这个接口。
 * 计量（L1）、限流熔断降级（L2）、语义缓存（L3）、指标（L4）都挂在这一层，
 * 所以任何一层出问题，都能通过对应的开关单独关掉，而不需要改业务代码。
 */
public interface ModelGateway {

    /** 非流式调用。 */
    GatewayResponse call(GatewayRequest request);

    /**
     * 需要结构化输出的非流式调用（协调器判断"该派哪个 Agent"用的就是它）。
     * 之所以单独开一个方法，是因为 {@code .entity(Class)} 的返回类型由框架反序列化决定，
     * 塞进 {@link GatewayResponse#content()} 再自己解析反而更容易出错。
     */
    <T> T callEntity(GatewayRequest request, Class<T> responseType);

    /** 流式调用。只包一层，不改变原有 SSE 的返回类型。 */
    Flux<String> stream(GatewayRequest request);
}
