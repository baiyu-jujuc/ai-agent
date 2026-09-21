package com.baiyu.agent.probe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 0 探针：确认供应商（DeepSeek，OpenAI 兼容接口）在非流式 / 流式调用下到底返不返回 usage。
 *
 * <p>默认跳过，避免 CI 和日常 {@code mvn verify} 真实消耗 token。只在需要采集结论时手动执行：
 *
 * <pre>
 * mvn -B -Dprobe.provider.usage=true -Dtest=ProviderUsageProbeTest test
 * </pre>
 *
 * <p>API Key 取自环境变量 {@code DEEPSEEK_API_KEY}；若不存在则回退读取仓库根目录的 {@code .env}
 * （该文件已被 .gitignore 忽略）。Key 只用于本次调用，不打印、不落库。
 */
@EnabledIfSystemProperty(named = "probe.provider.usage", matches = "true")
class ProviderUsageProbeTest {

    private static final String TAG = "[PROBE]";
    private static final int TIMEOUT_SECONDS = 60;
    private static final StringBuilder REPORT = new StringBuilder();
    private static final Path REPORT_FILE = Path.of("target", "probe", "provider-usage-probe.txt");

    private static String apiKey;
    private static String baseUrl;
    private static String modelName;

    /** 控制台在 Windows 下是 GBK，中文会乱码；这份 UTF-8 文件才是可留档的证据。 */
    private static void report(String line) {
        System.out.println(line);
        REPORT.append(line).append(System.lineSeparator());
    }

    @AfterAll
    static void writeReport() throws IOException {
        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(REPORT_FILE, REPORT.toString(), StandardCharsets.UTF_8);
        System.out.println(TAG + " 报告已写入 " + REPORT_FILE.toAbsolutePath());
    }

    @BeforeAll
    static void loadCredentials() throws IOException {
        Map<String, String> dotenv = readDotEnv();

        apiKey = firstNonBlank(System.getenv("DEEPSEEK_API_KEY"), dotenv.get("DEEPSEEK_API_KEY"));
        baseUrl = firstNonBlank(System.getenv("SPRING_AI_OPENAI_BASE_URL"),
                System.getenv("OPENAI_BASE_URL"), "https://api.deepseek.com");
        modelName = firstNonBlank(System.getenv("DEFAULT_MODEL"), dotenv.get("DEFAULT_MODEL"),
                System.getenv("SPRING_AI_OPENAI_CHAT_MODEL"), "deepseek-chat");

        if (apiKey == null) {
            throw new IllegalStateException("未找到 DEEPSEEK_API_KEY（环境变量与 .env 都没有），探针无法执行");
        }
        report(TAG + " baseUrl=" + baseUrl + " model=" + modelName
                + " apiKey=已加载(长度" + apiKey.length() + ")");
        // ChatOptions 的流式 usage 开关默认值：决定"要不要显式配置"
        report(TAG + " OpenAiChatOptions.builder().build().getStreamUsage() = "
                + OpenAiChatOptions.builder().build().getStreamUsage());
    }

    @Test
    void probeNonStreamingUsage() {
        ChatClient client = ChatClient.builder(chatModel(defaultOptions())).build();

        ChatResponse response = client.prompt()
                .user("请只回答两个字：收到")
                .call()
                .chatResponse();

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        report(TAG + " ===== 非流式 =====");
        report(TAG + " answer=" + text(response));
        report(TAG + " model=" + (response.getMetadata() == null ? null : response.getMetadata().getModel()));
        printUsage(usage);
    }

    @Test
    void probeStreamingUsageWithDefaultOptions() {
        streamProbe("流式（默认 options）", defaultOptions());
    }

    @Test
    void probeStreamingUsageFlagOn() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .maxTokens(64)
                .streamUsage(true)
                .build();
        streamProbe("流式（streamUsage=true）", options);
    }

    private void streamProbe(String label, OpenAiChatOptions options) {
        ChatClient client = ChatClient.builder(chatModel(options)).build();
        List<ChatResponse> chunks = client.prompt()
                .user("请只回答两个字：收到")
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofSeconds(TIMEOUT_SECONDS));

        report(TAG + " ===== " + label + " =====");
        if (chunks == null) {
            report(TAG + " 流式结果为空（超时或异常）");
            return;
        }
        long withUsage = chunks.stream()
                .filter(c -> c.getMetadata() != null && c.getMetadata().getUsage() != null)
                .count();
        long withNonZeroTotal = chunks.stream()
                .filter(c -> c.getMetadata() != null && c.getMetadata().getUsage() != null)
                .filter(c -> {
                    Integer total = c.getMetadata().getUsage().getTotalTokens();
                    return total != null && total > 0;
                })
                .count();

        report(TAG + " chunk 总数=" + chunks.size()
                + "，带 usage 的 chunk=" + withUsage
                + "，usage.totalTokens>0 的 chunk=" + withNonZeroTotal);

        for (int i = 0; i < chunks.size(); i++) {
            ChatResponse chunk = chunks.get(i);
            Usage chunkUsage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
            report(TAG + "   chunk[" + i + "] text=\"" + text(chunk).replace("\n", "\\n") + "\""
                    + " usage=" + (chunkUsage == null
                            ? "null"
                            : "prompt=" + chunkUsage.getPromptTokens()
                                    + ",completion=" + chunkUsage.getCompletionTokens()
                                    + ",total=" + chunkUsage.getTotalTokens()));
        }

        ChatResponse last = chunks.get(chunks.size() - 1);
        report(TAG + " 最后一片文本=" + text(last));
        printUsage(last.getMetadata() == null ? null : last.getMetadata().getUsage());
    }

    private static void printUsage(Usage usage) {
        if (usage == null) {
            report(TAG + " usage = null（拿不到）");
            return;
        }
        report(TAG + " usage: promptTokens=" + usage.getPromptTokens()
                + ", completionTokens=" + usage.getCompletionTokens()
                + ", totalTokens=" + usage.getTotalTokens());
        Object nativeUsage = usage.getNativeUsage();
        report(TAG + " nativeUsage=" + (nativeUsage == null ? "null" : nativeUsage));
    }

    private static String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "null";
        }
        return response.getResult().getOutput().getText();
    }

    private static OpenAiChatOptions defaultOptions() {
        return OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .maxTokens(64)
                .build();
    }

    private static OpenAiChatModel chatModel(OpenAiChatOptions options) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(TIMEOUT_SECONDS * 1000);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    private static Map<String, String> readDotEnv() throws IOException {
        Map<String, String> values = new HashMap<>();
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            report(TAG + " 未发现 .env，仅使用环境变量");
            return values;
        }
        for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            // 去掉可能存在的包裹引号
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        report(TAG + " 已读取 .env，键数量=" + values.size());
        return values;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
