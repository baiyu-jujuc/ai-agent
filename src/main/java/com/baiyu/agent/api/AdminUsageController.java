package com.baiyu.agent.api;

import com.baiyu.agent.gateway.UsageQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 用量查询接口（只读）。用来回答"这段时间花了多少钱、花在哪个场景上"。
 * 管理接口只做 REST + curl 验证，前端 Vue 工程不在本次范围内。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUsageController {

    private final UsageQueryService usageQueryService;

    public AdminUsageController(UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    @GetMapping("/usage")
    public UsageQueryService.UsageReport usage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String scene) {
        return usageQueryService.report(from, to, spaceId, scene);
    }
}
