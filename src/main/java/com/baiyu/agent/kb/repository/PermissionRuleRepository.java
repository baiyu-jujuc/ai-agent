package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.PermissionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PermissionRuleRepository extends JpaRepository<PermissionRule, String> {
    List<PermissionRule> findBySpaceId(String spaceId);
}
