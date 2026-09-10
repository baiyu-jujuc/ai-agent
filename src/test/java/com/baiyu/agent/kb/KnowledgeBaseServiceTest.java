package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.*;
import com.baiyu.agent.kb.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class KnowledgeBaseServiceTest {

    private KnowledgeBaseService kbService;
    private KnowledgeSpaceRepository spaceRepo;
    private DocumentRepository docRepo;
    private DocumentVersionRepository versionRepo;
    private ChunkRepository chunkRepo;
    private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        spaceRepo = mock(KnowledgeSpaceRepository.class);
        docRepo = mock(DocumentRepository.class);
        versionRepo = mock(DocumentVersionRepository.class);
        chunkRepo = mock(ChunkRepository.class);
        vectorStore = mock(VectorStore.class);

        doNothing().when(vectorStore).add(anyList());

        kbService = new KnowledgeBaseService(spaceRepo, docRepo, versionRepo, chunkRepo, vectorStore);

        // Simulate JPA save behavior
        when(spaceRepo.save(any(KnowledgeSpace.class))).thenAnswer(inv -> {
            KnowledgeSpace s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", "space-001");
            return s;
        });
        when(docRepo.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", "doc-001");
            return d;
        });
        when(versionRepo.save(any(DocumentVersion.class))).thenAnswer(inv -> {
            DocumentVersion v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", "ver-001");
            return v;
        });
        when(chunkRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createSpace() {
        KnowledgeSpace space = kbService.createSpace("Test Space", "desc", "team", null);
        assertNotNull(space);
        assertEquals("Test Space", space.getName());
        assertEquals("team", space.getVisibility());
        verify(spaceRepo).save(any(KnowledgeSpace.class));
    }

    @Test
    void createSpaceEmptyNameFails() {
        assertThrows(IllegalArgumentException.class, () -> kbService.createSpace("", "desc", "team", null));
    }

    @Test
    void uploadDocumentCreatesDocVersionAndChunks() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "Hello world content".getBytes());

        Document doc = kbService.uploadDocument("space-001", file);

        assertNotNull(doc);
        assertEquals("test.txt", doc.getFilename());
        assertEquals("ready", doc.getStatus());
        verify(docRepo, org.mockito.Mockito.atLeast(1)).save(any(Document.class));
        verify(versionRepo, org.mockito.Mockito.atLeast(1)).save(any(DocumentVersion.class));
        verify(chunkRepo).saveAll(anyList());
        verify(vectorStore).add(anyList());
    }

    @Test
    void uploadSameFileTwiceCreatesVersion2() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "Second version content".getBytes());

        // First version already exists
        DocumentVersion v1 = new DocumentVersion("doc-001", "space-001", 1);
        ReflectionTestUtils.setField(v1, "id", "ver-001");
        when(versionRepo.findByDocumentIdOrderByVersionNoDesc("doc-001"))
                .thenReturn(List.of(v1));
        when(chunkRepo.findByVersionIdAndEnabledTrue("ver-001"))
                .thenReturn(List.of());

        Document doc = kbService.uploadDocument("space-001", file);

        // Version 2 should be created
        verify(versionRepo, atLeast(2)).save(any(DocumentVersion.class));
    }

    @Test
    void rollbackDeactivatesCurrentAndActivatesTarget() {
        DocumentVersion v1 = new DocumentVersion("doc-001", "space-001", 1);
        ReflectionTestUtils.setField(v1, "id", "ver-001");
        v1.setActive(true);

        DocumentVersion v2 = new DocumentVersion("doc-001", "space-001", 2);
        ReflectionTestUtils.setField(v2, "id", "ver-002");
        v2.setActive(true);

        when(versionRepo.findByDocumentIdOrderByVersionNoDesc("doc-001"))
                .thenReturn(List.of(v2, v1));
        when(versionRepo.findByDocumentIdAndVersionNo("doc-001", 1))
                .thenReturn(java.util.Optional.of(v1));
        when(chunkRepo.findByVersionIdAndEnabledTrue("ver-001"))
                .thenReturn(List.of());
        when(chunkRepo.findByVersionIdAndEnabledTrue("ver-002"))
                .thenReturn(List.of());

        DocumentVersion rolled = kbService.rollbackToVersion("doc-001", 1);

        assertEquals(1, rolled.getVersionNo());
        assertTrue(rolled.isActive());
        assertEquals(2, rolled.getRollbackFromVersion());
    }

    @Test
    void searchChunksReturnsResults() {
        org.springframework.ai.document.Document d1 = new org.springframework.ai.document.Document("Java virtual threads guide", Map.of(
                "space_id", "space-001",
                "document_id", "doc-001",
                "version_id", "ver-001",
                "chunk_id", "chunk-001",
                "chunk_index", 0
        ));
        org.springframework.ai.document.Document d2 = new org.springframework.ai.document.Document("Python data science", Map.of(
                "space_id", "space-001",
                "document_id", "doc-002",
                "version_id", "ver-001",
                "chunk_id", "chunk-002",
                "chunk_index", 0
        ));

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(d1, d2));

        List<Chunk> results = kbService.searchChunks("space-001", "Java threads", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getContent().contains("Java"));
        assertEquals("chunk-001", results.get(0).getId());
    }

    @Test
    void searchChunksFallsBackToDatabaseWhenVectorStoreIsEmpty() {
        Chunk persisted = new Chunk("ver-001", "space-001", "doc-001",
                "Java virtual threads are useful for IO-bound workloads", 0);
        persisted.setId("chunk-persisted");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of());
        when(chunkRepo.findBySpaceIdAndEnabledTrue("space-001"))
                .thenReturn(List.of(persisted));

        List<Chunk> results = kbService.searchChunks("space-001", "Java threads", 5);

        assertFalse(results.isEmpty());
        assertEquals("chunk-persisted", results.get(0).getId());
    }

    @Test
    void uploadEmptyFileFails() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> kbService.uploadDocument("space-001", file));
    }
}
