package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.ModelRouteConfig;
import com.baiyu.agent.gateway.repository.ModelRouteConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把默认路由写进数据库，<b>幂等</b>：已存在的 routeKey 不覆盖。
 *
 * <p>为什么不覆盖：这条记录可能已经被运维或你自己改过，
 * 重启就把人家的改动冲掉是典型的"自动化事故"。
 */
@Component
public class ModelRouteInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelRouteInitializer.class);
    private static final List<String> DEFAULT_ROUTE_KEYS = List.of("kb_qa", "default_chat");

    private final ModelRouteConfigRepository repository;
    private final ModelRouteService routeService;

    public ModelRouteInitializer(ModelRouteConfigRepository repository, ModelRouteService routeService) {
        this.repository = repository;
        this.routeService = routeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (String routeKey : DEFAULT_ROUTE_KEYS) {
            try {
                if (repository.findByRouteKey(routeKey).isPresent()) {
                    continue;
                }
                ModelRouteService.RouteDefinition definition = routeService.resolve(routeKey);
                repository.save(new ModelRouteConfig(routeKey, definition.primary(),
                        String.join(",", definition.fallbacks())));
                created++;
            } catch (Exception e) {
                // 初始化失败不能阻止应用启动：路由有三级回退，最差也能用 application.yml 的默认值
                log.warn("初始化路由 {} 失败（忽略，继续用默认配置）：{}", routeKey, e.toString());
            }
        }
        routeService.reload();
        log.info("模型路由初始化完成：新建 {} 条默认路由，当前路由={}", created,
                routeService.listRoutes().stream().map(ModelRouteService.RouteView::routeKey).toList());
    }
}
