package com.baiyu.agent.gateway;

import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.gateway.entity.ModelRouteConfig;
import com.baiyu.agent.gateway.repository.ModelRouteConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRouteServiceTest {

    private ModelRouteConfigRepository repository;
    private GatewayProperties properties;
    private ModelRegistry modelRegistry;
    private ModelRouteService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelRouteConfigRepository.class);
        properties = new GatewayProperties();
        modelRegistry = new ModelRegistry("model-default", "model-pro", "model-fast", "model-vision");
        service = new ModelRouteService(repository, properties, modelRegistry);
    }

    @Test
    void defaultsFallBackToModelRegistryWhenYmlIsEmpty() {
        assertEquals("model-pro", service.primaryOf("kb_qa"));
        assertEquals(List.of("model-fast"), service.fallbackChain("kb_qa"));
        assertEquals("model-default", service.primaryOf("default_chat"));
    }

    @Test
    void ymlRouteWinsOverBuiltInDefaults() {
        GatewayProperties.Route route = new GatewayProperties.Route();
        route.setPrimary("yml-primary");
        route.setFallbacks("yml-fallback-a, yml-fallback-b");
        properties.getRoutes().put("kb_qa", route);
        service.reload();

        assertEquals("yml-primary", service.primaryOf("kb_qa"));
        assertEquals(List.of("yml-fallback-a", "yml-fallback-b"), service.fallbackChain("kb_qa"));
    }

    @Test
    void databaseRouteWinsOverYml() {
        GatewayProperties.Route route = new GatewayProperties.Route();
        route.setPrimary("yml-primary");
        properties.getRoutes().put("kb_qa", route);
        when(repository.findAll()).thenReturn(List.of(
                new ModelRouteConfig("kb_qa", "db-primary", "db-fallback")));
        service.reload();

        assertEquals("db-primary", service.primaryOf("kb_qa"));
        assertEquals(List.of("db-fallback"), service.fallbackChain("kb_qa"));
    }

    @Test
    void candidatesPutExplicitModelFirstAndKeepFallbackChain() {
        GatewayProperties.Route route = new GatewayProperties.Route();
        route.setPrimary("route-primary");
        route.setFallbacks("route-fallback");
        properties.getRoutes().put("kb_qa", route);
        service.reload();

        assertEquals(List.of("explicit", "route-fallback"),
                service.candidates("kb_qa", "explicit"));
        assertEquals(List.of("route-primary", "route-fallback"),
                service.candidates("kb_qa", null));
        // 显式指定的模型正好等于降级链里的模型时，不能重复出现
        assertEquals(List.of("route-fallback"), service.candidates("kb_qa", "route-fallback"));
    }

    @Test
    void cacheAvoidsHittingDatabaseOnEveryCall() {
        service.candidates("kb_qa", null);
        service.candidates("kb_qa", null);
        service.candidates("kb_qa", null);

        verify(repository, times(1)).findAll();
    }

    @Test
    void databaseFailureFallsBackToDefaultsInsteadOfBreakingCalls() {
        when(repository.findAll()).thenThrow(new RuntimeException("table not ready"));
        service.reload();

        assertEquals("model-pro", service.primaryOf("kb_qa"));
    }

    @Test
    void updatePersistsAndTakesEffectImmediately() {
        when(repository.findByRouteKey("kb_qa")).thenReturn(Optional.empty());
        // reload() 之后从库里读到的就是刚写进去的那条（真实数据库的行为）
        when(repository.findAll()).thenReturn(List.of(
                new ModelRouteConfig("kb_qa", "new-primary", "new-fallback")));

        ModelRouteService.RouteView view = service.update("kb_qa", "new-primary", "new-fallback");

        assertEquals("new-primary", view.primary());
        verify(repository).save(any(ModelRouteConfig.class));
        assertTrue(view.enabled());
    }

    @Test
    void updateRejectsBlankPrimary() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.update("kb_qa", "  ", "x"));
    }
}
