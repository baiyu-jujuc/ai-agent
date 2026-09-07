package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findBySpaceId(String spaceId);
}
