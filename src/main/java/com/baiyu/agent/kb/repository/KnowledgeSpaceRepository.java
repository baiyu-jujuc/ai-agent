package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.KnowledgeSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeSpaceRepository extends JpaRepository<KnowledgeSpace, String> {
    Optional<KnowledgeSpace> findByName(String name);
    List<KnowledgeSpace> findByVisibility(String visibility);
}
