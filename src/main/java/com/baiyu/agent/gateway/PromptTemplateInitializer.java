package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.PromptTemplate;
import com.baiyu.agent.gateway.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时把内置模板写进数据库（幂等）：只在"这个 key 一条记录都没有"时写入 v1 并置为 active。
 * 已有记录时不覆盖——否则重启就会把你自己改过的 Prompt 冲掉。
 */
@Component
public class PromptTemplateInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateInitializer.class);

    private final PromptTemplateRepository repository;
    private final PromptTemplateService promptTemplateService;

    public PromptTemplateInitializer(PromptTemplateRepository repository,
                                     PromptTemplateService promptTemplateService) {
        this.repository = repository;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, String> entry : PromptTemplateService.builtInTemplates().entrySet()) {
            String key = entry.getKey();
            try {
                if (!repository.findByTemplateKeyOrderByVersionDesc(key).isEmpty()) {
                    continue;
                }
                repository.save(new PromptTemplate(key, 1, entry.getValue(), true, "内置模板（v1，初始化写入）"));
                log.info("Prompt 模板已初始化：{} v1", key);
            } catch (Exception e) {
                log.warn("初始化 Prompt 模板 {} 失败（忽略，继续用内置模板）：{}", key, e.toString());
            }
        }
        promptTemplateService.reload();
    }
}
