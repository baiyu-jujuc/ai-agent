package com.baiyu.agent.gateway.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网关测试的公共脚手架。
 *
 * <p>存在的理由：{@code ChatClient} 是链式 fluent API，每个测试都手写一遍
 * {@code prompt()/user()/call()/chatResponse()} 的 mock 会让测试充满噪音。
 * 这里把噪音收口，让每个测试只剩"断言什么"。
 */
public final class GatewayTestSupport {

    private GatewayTestSupport() {
    }

    /** 带真实 usage 的响应。 */
    public static ChatResponse responseWithUsage(String text, int promptTokens, int completionTokens) {
        return response(text, new DefaultUsage(promptTokens, completionTokens));
    }

    /** 不带 usage 的响应（模拟"供应商没返回用量"）。 */
    public static ChatResponse responseWithoutUsage(String text) {
        return response(text, null);
    }

    public static ChatResponse response(String text, Usage usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model("test-model");
        if (usage != null) {
            metadata.usage(usage);
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata.build());
    }

    /** 非流式 ChatClient：固定返回一个响应。 */
    public static ChatClient chatClient(ChatResponse response) {
        ClientWithSpec clientWithSpec = clientAndSpec();
        stubCall(clientWithSpec.spec(), response);
        return clientWithSpec.client();
    }

    /** 需要自己控制 {@code spec.call()} 行为（例如"第一次失败、第二次成功"）时用这个。 */
    public static ClientWithSpec clientAndSpec() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = spec(client);
        return new ClientWithSpec(client, spec);
    }

    /** 让 {@code spec.call()} 返回给定响应。 */
    public static void stubCall(ChatClient.ChatClientRequestSpec spec, ChatResponse response) {
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(response);
        when(callSpec.content()).thenReturn(response.getResult().getOutput().getText());
    }

    public record ClientWithSpec(ChatClient client, ChatClient.ChatClientRequestSpec spec) {
    }

    /** 非流式 ChatClient：调用时抛异常（模拟上游不可用）。 */
    public static ChatClient failingChatClient(RuntimeException error) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = spec(client);
        when(spec.call()).thenThrow(error);
        return client;
    }

    /** 结构化输出 ChatClient：{@code call().responseEntity(...)} 返回给定实体。 */
    public static <T> ChatClient entityChatClient(ChatResponse response, T entity) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = spec(client);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(spec.call()).thenReturn(callSpec);
        // 三个 responseEntity 重载会让 any() 产生歧义，这里显式指定 Class 版本
        when(callSpec.responseEntity(any(Class.class))).thenReturn(new ResponseEntity<>(response, entity));
        return client;
    }

    /** 流式 ChatClient。 */
    public static ChatClient streamingChatClient(Flux<ChatResponse> chunks) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = spec(client);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(chunks);
        return client;
    }

    private static ChatClient.ChatClientRequestSpec spec(ChatClient client) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.tools(any())).thenReturn(spec);
        return spec;
    }

    /** 空的 Spring {@code ObjectProvider}：网关用它表示"这个可选依赖不存在"。 */
    @SuppressWarnings("unchecked")
    public static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
