package com.baiyu.agent.kb.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_permission_rule")
public class PermissionRule {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "space_id", nullable = false)
    private String spaceId;

    // user:<id> | role:reader | role:writer | role:admin | *
    @Column(name = "principal", nullable = false)
    private String principal;

    // space | document
    @Column(name = "resource_type", nullable = false)
    private String resourceType = "space";

    @Column(name = "resource_id")
    private String resourceId;

    // read | write | delete
    @Column(nullable = false)
    private String action = "read";

    // allow | deny
    @Column(nullable = false)
    private String effect = "allow";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public PermissionRule() {}

    public PermissionRule(String spaceId, String principal, String action, String effect) {
        this.spaceId = spaceId;
        this.principal = principal;
        this.action = action;
        this.effect = effect;
    }

    public String getId() { return id; }
    public String getSpaceId() { return spaceId; }
    public String getPrincipal() { return principal; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getAction() { return action; }
    public String getEffect() { return effect; }
    public Instant getCreatedAt() { return createdAt; }
}
