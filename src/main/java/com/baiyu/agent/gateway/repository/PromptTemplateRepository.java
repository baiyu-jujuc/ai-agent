package com.baiyu.agent.gateway.repository;

import com.baiyu.agent.gateway.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, String> {

    Optional<PromptTemplate> findFirstByTemplateKeyAndActiveTrueOrderByVersionDesc(String templateKey);

    Optional<PromptTemplate> findByTemplateKeyAndVersion(String templateKey, Integer version);

    List<PromptTemplate> findByTemplateKeyOrderByVersionDesc(String templateKey);

    List<PromptTemplate> findAllByOrderByTemplateKeyAscVersionDesc();

    Optional<PromptTemplate> findFirstByTemplateKeyOrderByVersionDesc(String templateKey);
}
