package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.PromptTemplate;
import com.baiyu.agent.gateway.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptTemplateServiceTest {

    private PromptTemplateRepository repository;
    private GatewayProperties properties;
    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(PromptTemplateRepository.class);
        properties = new GatewayProperties();
        service = new PromptTemplateService(repository, properties);
    }

    @Test
    void builtInTemplatesCoverBothKeys() {
        assertEquals(2, PromptTemplateService.builtInTemplates().size());
        assertTrue(PromptTemplateService.builtInTemplates()
                .containsKey(PromptTemplateService.KEY_KB_QA_PLAIN));
        assertTrue(PromptTemplateService.builtInTemplates()
                .containsKey(PromptTemplateService.KEY_KB_QA_WITH_HISTORY));
    }

    @Test
    void renderReplacesAllPlaceholdersUsingBuiltInTemplate() {
        properties.setPromptStoreEnabled(false);

        PromptTemplateService.RenderedPrompt rendered = service.render(
                PromptTemplateService.KEY_KB_QA_PLAIN,
                Map.of("context", "上下文内容", "question", "问题内容", "history", ""));

        assertTrue(rendered.text().contains("上下文内容"));
        assertTrue(rendered.text().contains("问题内容"));
        assertFalse(rendered.text().contains("{context}"), "占位符必须被替换掉");
        assertFalse(rendered.text().contains("{question}"));
        assertEquals(1, rendered.version(), "内置模板记为 v1");
        assertEquals("built-in", rendered.source());
    }

    @Test
    void historyTemplateIsUsedWhenHistoryExists() {
        properties.setPromptStoreEnabled(false);

        PromptTemplateService.RenderedPrompt rendered = service.render(
                PromptTemplateService.KEY_KB_QA_WITH_HISTORY,
                Map.of("context", "ctx", "question", "q", "history", "用户: 之前的问题"));

        assertTrue(rendered.text().contains("对话历史"));
        assertTrue(rendered.text().contains("之前的问题"));
    }

    @Test
    void renderUsesActiveDatabaseTemplateWhenPresent() {
        when(repository.findFirstByTemplateKeyAndActiveTrueOrderByVersionDesc(
                PromptTemplateService.KEY_KB_QA_PLAIN))
                .thenReturn(Optional.of(new PromptTemplate(PromptTemplateService.KEY_KB_QA_PLAIN, 3,
                        "自定义模板: {question} / {context}", true, "v3")));

        PromptTemplateService.RenderedPrompt rendered = service.render(
                PromptTemplateService.KEY_KB_QA_PLAIN,
                Map.of("context", "C", "question", "Q"));

        assertEquals("自定义模板: Q / C", rendered.text());
        assertEquals(3, rendered.version());
        assertEquals("database", rendered.source());
    }

    @Test
    void fallsBackToBuiltInWhenDatabaseThrows() {
        when(repository.findFirstByTemplateKeyAndActiveTrueOrderByVersionDesc(any()))
                .thenThrow(new RuntimeException("table not ready"));

        PromptTemplateService.RenderedPrompt rendered = service.render(
                PromptTemplateService.KEY_KB_QA_PLAIN, Map.of("context", "C", "question", "Q"));

        assertEquals("built-in", rendered.source());
    }

    @Test
    void createVersionAssignsNextVersionAndKeepsItInactive() {
        when(repository.findFirstByTemplateKeyOrderByVersionDesc(PromptTemplateService.KEY_KB_QA_PLAIN))
                .thenReturn(Optional.of(new PromptTemplate(PromptTemplateService.KEY_KB_QA_PLAIN, 2,
                        "old", true, "v2")));
        when(repository.save(any(PromptTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplate created = service.createVersion(
                PromptTemplateService.KEY_KB_QA_PLAIN, "新模板 {question}", "调整措辞");

        assertEquals(3, created.getVersion());
        assertFalse(created.isActive(), "新版本默认不生效，避免一提交就被线上用上");
    }

    @Test
    void activateSwitchesActiveFlagAndRefreshesCache() {
        PromptTemplate v1 = new PromptTemplate(PromptTemplateService.KEY_KB_QA_PLAIN, 1, "v1", true, null);
        PromptTemplate v2 = new PromptTemplate(PromptTemplateService.KEY_KB_QA_PLAIN, 2, "v2", false, null);
        when(repository.findByTemplateKeyOrderByVersionDesc(PromptTemplateService.KEY_KB_QA_PLAIN))
                .thenReturn(List.of(v2, v1));

        PromptTemplate activated = service.activate(PromptTemplateService.KEY_KB_QA_PLAIN, 2);

        assertEquals(2, activated.getVersion());
        assertTrue(v2.isActive());
        assertFalse(v1.isActive(), "切版本时旧版本必须置为 inactive");
        verify(repository).saveAll(any());
    }

    @Test
    void activateRejectsUnknownVersion() {
        when(repository.findByTemplateKeyOrderByVersionDesc(any()))
                .thenReturn(List.of(new PromptTemplate("k", 1, "v1", true, null)));

        assertThrows(IllegalArgumentException.class, () -> service.activate("k", 99));
    }

    @Test
    void unknownTemplateKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.render("no_such_key", Map.of()));
    }
}
