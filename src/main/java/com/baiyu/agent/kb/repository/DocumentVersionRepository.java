package com.baiyu.agent.kb.repository;

import com.baiyu.agent.kb.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, String> {
    List<DocumentVersion> findByDocumentIdOrderByVersionNoDesc(String documentId);
    Optional<DocumentVersion> findByDocumentIdAndActiveTrue(String documentId);
    Optional<DocumentVersion> findByDocumentIdAndVersionNo(String documentId, int versionNo);
}
