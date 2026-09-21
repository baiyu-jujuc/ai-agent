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
 * Prompt 模板。
 *
 * <p>为什么要入库：Prompt 是会影响效果和成本的<b>资产</b>，不是普通字符串常量。
 * 硬编码在 Java 文本块里时，"改一个字就要发版"，而且改完无法回答
 * "这两天的效果变化是不是这次改 Prompt 引起的"——因为变量没有控制住。
 *
 * <p>入库之后每个版本有版本号，每次调用把 {@code promptKey + version} 写进用量记录，
 * 效果对比时就能把 Prompt 版本当成自变量。
 */
@Entity
@Table(name = "prompt_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_prompt_key_version",
                columnNames = {"template_key", "version"}))
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    @Column(name = "template_key", length = 64, nullable = false)
    private String templateKey;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** 模板正文，含 {context} / {history} / {question} 占位符。 */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PromptTemplate() {
    }

    public PromptTemplate(String templateKey, Integer version, String content, boolean active, String description) {
        this.templateKey = templateKey;
        this.version = version;
        this.content = content;
        this.active = active;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public void setTemplateKey(String templateKey) {
        this.templateKey = templateKey;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
