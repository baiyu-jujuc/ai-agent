package com.baiyu.agent.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating per-request ChatModel instances with custom API keys.
 * Used when allow-client-model-key is enabled so each request can use its own model key
 * without polluting the global singleton or logs.
 */
@Component
public class ChatModelFactory {

    private final String baseUrl;
    private final OpenAiChatOptions defaultOptions;
    private final int timeoutSeconds;

    public ChatModelFactory(
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl,
            OpenAiChatOptions defaultOptions,
            @Value("${agent.timeout-seconds:60}") int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.defaultOptions = defaultOptions;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Create a ChatModel that uses the given API key for this request only.
     * The key is not stored anywhere beyond the returned model instance.
     *
     * @param apiKey the per-request API key (never logged or persisted)
     * @return a new ChatModel instance configured with the given key
     */
    public ChatModel createChatModel(String apiKey) {
        SimpleClientHttpRequestFactory restFactory = new SimpleClientHttpRequestFactory();
        restFactory.setConnectTimeout(15000);
        restFactory.setReadTimeout(timeoutSeconds * 1000);

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                );

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(restFactory))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(httpClient)))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaultOptions)
                .build();
    }

    /**
     * Create a ChatModel with a specific model name override.
     */
    public ChatModel createChatModel(String apiKey, String modelName) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(defaultOptions.getTemperature())
                .maxTokens(defaultOptions.getMaxTokens())
                .build();

        SimpleClientHttpRequestFactory restFactory = new SimpleClientHttpRequestFactory();
        restFactory.setConnectTimeout(15000);
        restFactory.setReadTimeout(timeoutSeconds * 1000);

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                );

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(restFactory))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(httpClient)))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
