package com.baiyu.agent.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 模型路由配置：一个 routeKey 对应"主模型 + 降级链"。
 *
 * <p>不引入 Nacos/Apollo 这类配置中心，是因为这个项目的规模用"数据库表 + 本地缓存"就够了。
 * 表里的记录优先于 {@code application.yml}，yml 又优先于代码里的内置默认值。
 */
@Entity
@Table(name = "model_route_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_route_key", columnNames = "route_key"))
public class ModelRouteConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    @Column(name = "route_key", length = 64, nullable = false)
    private String routeKey;

    @Column(name = "primary_model", length = 64, nullable = false)
    private String primaryModel;

    /** 降级链，逗号分隔，例如 "deepseek-v4-flash,deepseek-chat"。 */
    @Column(name = "fallback_models", length = 255)
    private String fallbackModels;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ModelRouteConfig() {
    }

    public ModelRouteConfig(String routeKey, String primaryModel, String fallbackModels) {
        this.routeKey = routeKey;
        this.primaryModel = primaryModel;
        this.fallbackModels = fallbackModels;
        this.enabled = true;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public void setRouteKey(String routeKey) {
        this.routeKey = routeKey;
    }

    public String getPrimaryModel() {
        return primaryModel;
    }

    public void setPrimaryModel(String primaryModel) {
        this.primaryModel = primaryModel;
    }

    public String getFallbackModels() {
        return fallbackModels;
    }

    public void setFallbackModels(String fallbackModels) {
        this.fallbackModels = fallbackModels;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
