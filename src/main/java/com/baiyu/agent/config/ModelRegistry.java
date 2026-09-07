package com.baiyu.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ModelRegistry {

    private final String defaultModel;
    private final String proModel;
    private final String fastModel;
    private final String visionModel;

    public ModelRegistry(
            @Value("${agent.default-model:deepseek-v4-flash}") String defaultModel,
            @Value("${agent.pro-model:deepseek-v4-pro}") String proModel,
            @Value("${agent.fast-model:deepseek-v4-flash}") String fastModel,
            @Value("${agent.vision-model:deepseek-v4-flash-vision-exp}") String visionModel
    ) {
        this.defaultModel = defaultModel;
        this.proModel = proModel;
        this.fastModel = fastModel;
        this.visionModel = visionModel;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public List<Map<String, String>> listModels() {
        List<Map<String, String>> models = new ArrayList<>();
        models.add(modelEntry(fastModel, "DeepSeek V4 Flash (低成本)", "快速响应，适合日常对话"));
        models.add(modelEntry(proModel, "DeepSeek V4 Pro (高性能)", "最强推理能力，适合复杂任务"));
        models.add(modelEntry(visionModel, "DeepSeek V4 Vision", "支持图像输入(实验)"));
        return models;
    }

    public boolean isValidModel(String modelId) {
        return modelId != null && !modelId.isBlank();
    }

    private Map<String, String> modelEntry(String id, String name, String description) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        return m;
    }
}
