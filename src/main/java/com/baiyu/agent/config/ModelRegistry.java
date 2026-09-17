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

    /** 高性能模型：复杂任务的候选主模型。 */
    public String getProModel() {
        return proModel;
    }

    /**
     * 轻量模型：网关降级链的默认兜底。
     * 改造前这个字段只用于前端展示，没有任何地方拿它做降级——这也是本次升级要补上的能力。
     */
    public String getFastModel() {
        return fastModel;
    }

    public String getVisionModel() {
        return visionModel;
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
