package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.PromptTemplate;
import com.baiyu.agent.gateway.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板服务：取当前生效版本、渲染、建新版本、切换、回滚。
 *
 * <p>设计要点：
 * <ul>
 *   <li>数据库里没有时回退到<b>代码内置模板</b>（内容就是改造前硬编码的那两段），
 *       所以"模板表是空的"不会导致问答失败；</li>
 *   <li>切换版本时把同一个 key 的其他版本置为 inactive，保证"一个 key 只有一个 active"；</li>
 *   <li>本地缓存 + 切换时失效，避免每次问答都查一次库。</li>
 * </ul>
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    public static final String KEY_KB_QA_PLAIN = "kb_qa_plain";
    public static final String KEY_KB_QA_WITH_HISTORY = "kb_qa_with_history";

    /** 内置默认模板：与改造前 KbQaService.buildPrompt() 的文本保持一致。 */
    private static final Map<String, String> BUILT_IN = Map.of(
            KEY_KB_QA_PLAIN, """
                    基于以下知识库内容回答问题。如果内容中没有相关信息，请明确说明"知识库中未找到相关内容"。

                    知识库内容:
                    {context}

                    问题: {question}

                    请给出准确、简洁的回答，并在末尾标注引用的来源编号 [1], [2] 等。
                    """,
            KEY_KB_QA_WITH_HISTORY, """
                    基于以下知识库内容和对话历史回答问题。如果知识库内容中没有相关信息，请明确说明"知识库中未找到相关内容"。
                    结合对话历史理解用户的追问意图。

                    {history}
                    知识库内容:
                    {context}

                    问题: {question}

                    请给出准确、简洁的回答，并在末尾标注引用的来源编号 [1], [2] 等。
                    """);

    private final PromptTemplateRepository repository;
    private final GatewayProperties properties;
    private final Map<String, ActiveTemplate> cache = new ConcurrentHashMap<>();

    public PromptTemplateService(PromptTemplateRepository repository, GatewayProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 渲染模板并返回"用了哪个 key、哪个版本"，后者要写进用量记录。 */
    public RenderedPrompt render(String templateKey, Map<String, String> variables) {
        ActiveTemplate template = activeTemplate(templateKey);
        String text = template.content();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return new RenderedPrompt(text, template.templateKey(), template.version(), template.source());
    }

    public ActiveTemplate activeTemplate(String templateKey) {
        if (!properties.isPromptStoreEnabled()) {
            return builtIn(templateKey);
        }
        ActiveTemplate cached = cache.get(templateKey);
        if (cached != null) {
            return cached;
        }
        ActiveTemplate loaded = loadActive(templateKey);
        cache.put(templateKey, loaded);
        return loaded;
    }

    /** 全部版本，供管理接口展示（哪个 key 有哪些版本、当前生效的是哪个）。 */
    public List<PromptTemplate> listAll() {
        return repository.findAllByOrderByTemplateKeyAscVersionDesc();
    }

    /** 新建一个版本，默认不生效（避免"一提交就被线上用上"）。 */
    @Transactional
    public PromptTemplate createVersion(String templateKey, String content, String description) {
        if (templateKey == null || templateKey.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("templateKey 与 content 不能为空");
        }
        int nextVersion = repository.findFirstByTemplateKeyOrderByVersionDesc(templateKey)
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
        PromptTemplate template = new PromptTemplate(templateKey, nextVersion, content, false, description);
        PromptTemplate saved = repository.save(template);
        log.info("新增 Prompt 模板版本：{} v{}（未生效）", templateKey, nextVersion);
        return saved;
    }

    /** 切换生效版本：同 key 其他版本全部置为 inactive。 */
    @Transactional
    public PromptTemplate activate(String templateKey, Integer version) {
        List<PromptTemplate> versions = repository.findByTemplateKeyOrderByVersionDesc(templateKey);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("模板不存在: " + templateKey);
        }
        PromptTemplate target = versions.stream()
                .filter(template -> template.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "模板版本不存在: " + templateKey + " v" + version));
        for (PromptTemplate template : versions) {
            template.setActive(template.getVersion().equals(version));
        }
        repository.saveAll(versions);
        cache.remove(templateKey);
        log.info("Prompt 模板已切换：{} → v{}", templateKey, version);
        return target;
    }

    /** 清空本地缓存，下次调用重新从库里取。 */
    public void reload() {
        cache.clear();
    }

    private ActiveTemplate loadActive(String templateKey) {
        try {
            return repository.findFirstByTemplateKeyAndActiveTrueOrderByVersionDesc(templateKey)
                    .map(template -> new ActiveTemplate(templateKey, template.getVersion(),
                            template.getContent(), "database"))
                    .orElseGet(() -> {
                        log.warn("模板 {} 在数据库里没有生效版本，回退到内置模板", templateKey);
                        return builtIn(templateKey);
                    });
        } catch (Exception e) {
            log.warn("读取模板 {} 失败，回退到内置模板：{}", templateKey, e.toString());
            return builtIn(templateKey);
        }
    }

    private ActiveTemplate builtIn(String templateKey) {
        String content = BUILT_IN.get(templateKey);
        if (content == null) {
            throw new IllegalArgumentException("未知的 Prompt 模板 key: " + templateKey);
        }
        return new ActiveTemplate(templateKey, 1, content, "built-in");
    }

    /** 内置模板（初始化器用它往库里塞 v1，与代码里的默认值保持同源）。 */
    public static Map<String, String> builtInTemplates() {
        return new LinkedHashMap<>(BUILT_IN);
    }

    public record ActiveTemplate(String templateKey, Integer version, String content, String source) {
    }

    public record RenderedPrompt(String text, String templateKey, Integer version, String source) {
    }
}
