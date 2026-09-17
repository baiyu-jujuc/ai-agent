package com.baiyu.agent.api;

import com.baiyu.agent.config.ModelRegistry;
import com.baiyu.agent.gateway.GatewayProperties;
import com.baiyu.agent.gateway.ModelRouteService;
import com.baiyu.agent.gateway.PricingCalculator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型路由与单价的管理接口（只做 REST，模型清单/单价/当前生效路由都用 curl 验证）。
 *
 * <p>这里刻意不做"图形化配置中心"：配置改动频率很低，为它写前端不划算；
 * 但"改了要能立刻生效、不用重启"是刚需，所以有了 {@code /reload}。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminModelController {

    private final ModelRouteService routeService;
    private final ModelRegistry modelRegistry;
    private final PricingCalculator pricing;
    private final GatewayProperties properties;

    public AdminModelController(ModelRouteService routeService,
                                ModelRegistry modelRegistry,
                                PricingCalculator pricing,
                                GatewayProperties properties) {
        this.routeService = routeService;
        this.modelRegistry = modelRegistry;
        this.pricing = pricing;
        this.properties = properties;
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routes", routeService.listRoutes());
        result.put("available", modelRegistry.listModels());
        result.put("pricing", pricingView());
        result.put("resilienceEnabled", properties.isResilienceEnabled());
        return result;
    }

    @PutMapping("/models/{routeKey}")
    public ModelRouteService.RouteView update(@PathVariable String routeKey,
                                              @RequestBody Map<String, String> body) {
        return routeService.update(routeKey, body.get("primary"), body.get("fallbacks"));
    }

    @PostMapping("/models/reload")
    public Map<String, Object> reload() {
        routeService.reload();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "reloaded");
        result.put("routes", routeService.listRoutes());
        return result;
    }

    /** 展示当前单价表（单位：微元 / 百万 token），方便和用量记录里的单价快照对照。 */
    public Map<String, Object> pricingView() {
        Map<String, Object> view = new LinkedHashMap<>();
        for (Map.Entry<String, GatewayProperties.Price> entry : properties.getPricing().getModels().entrySet()) {
            PricingCalculator.UnitPrice price = pricing.priceOf(entry.getKey());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("promptPerMillion", price.promptPerMillion());
            detail.put("completionPerMillion", price.completionPerMillion());
            view.put(entry.getKey(), detail);
        }
        return view;
    }
}
