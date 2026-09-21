package com.baiyu.agent.api;

import com.baiyu.agent.gateway.PromptTemplateService;
import com.baiyu.agent.gateway.entity.PromptTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理接口。改模板、切版本、回滚都走这里，不需要重新发版。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPromptController {

    private final PromptTemplateService promptTemplateService;

    public AdminPromptController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    @GetMapping("/prompts")
    public Map<String, Object> list() {
        List<PromptTemplate> templates = promptTemplateService.listAll();
        return Map.of(
                "count", templates.size(),
                "items", templates.stream().map(this::view).toList());
    }

    @PostMapping("/prompts")
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        PromptTemplate created = promptTemplateService.createVersion(
                body.get("key"), body.get("content"), body.get("description"));
        return view(created);
    }

    @PostMapping("/prompts/{key}/activate")
    public Map<String, Object> activate(@PathVariable String key, @RequestParam Integer version) {
        return view(promptTemplateService.activate(key, version));
    }

    private Map<String, Object> view(PromptTemplate template) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("key", template.getTemplateKey());
        map.put("version", template.getVersion());
        map.put("active", template.isActive());
        map.put("description", template.getDescription());
        map.put("createdAt", template.getCreatedAt());
        map.put("contentPreview", abbreviate(template.getContent()));
        return map;
    }

    private String abbreviate(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= 80 ? content : content.substring(0, 80) + "...";
    }
}
