package com.baiyu.agent.kb;

import com.baiyu.agent.kb.entity.*;
import com.baiyu.agent.kb.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 100;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final double MIN_SEARCH_SCORE = 0.0001;

    private final KnowledgeSpaceRepository spaceRepo;
    private final DocumentRepository docRepo;
    private final DocumentVersionRepository versionRepo;
    private final ChunkRepository chunkRepo;
    private final VectorStore vectorStore;

    public KnowledgeBaseService(
            KnowledgeSpaceRepository spaceRepo,
            DocumentRepository docRepo,
            DocumentVersionRepository versionRepo,
            ChunkRepository chunkRepo,
            VectorStore vectorStore) {
        this.spaceRepo = spaceRepo;
        this.docRepo = docRepo;
        this.versionRepo = versionRepo;
        this.chunkRepo = chunkRepo;
        this.vectorStore = vectorStore;
    }

    @Transactional
    public KnowledgeSpace createSpace(String name, String description, String visibility, String embeddingModel) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("空间名称不能为空");
        }
        KnowledgeSpace space = new KnowledgeSpace(name, description);
        if (visibility != null) space.setVisibility(visibility);
        if (embeddingModel != null) space.setEmbeddingModel(embeddingModel);
        return spaceRepo.save(space);
    }

    public List<KnowledgeSpace> listSpaces() {
        return spaceRepo.findAll();
    }

    public KnowledgeSpace getSpace(String spaceId) {
        return spaceRepo.findById(spaceId)
                .orElseThrow(() -> new IllegalArgumentException("知识空间不存在: " + spaceId));
    }

    public List<Document> listDocuments(String spaceId) {
        return docRepo.findBySpaceId(spaceId);
    }

    @Transactional
    public Document uploadDocument(String spaceId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件过大 (上限 " + (MAX_FILE_SIZE / 1024 / 1024) + "MB)");
        }

        String filename = file.getOriginalFilename();
        String mimeType = file.getContentType();
        String content = extractText(file, filename);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("无法从文件中提取文本内容");
        }
        String hash = sha256(file.getBytes());

        Document doc = docRepo.findBySpaceIdAndFilename(spaceId, filename)
                .orElseGet(() -> new Document(spaceId, filename, mimeType));
        doc.setStatus("parsing");
        doc = docRepo.save(doc);

        List<DocumentVersion> existing = versionRepo.findByDocumentIdOrderByVersionNoDesc(doc.getId());
        int versionNo = existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1;
        for (DocumentVersion v : existing) {
            v.setActive(false);
            versionRepo.save(v);
            disableChunksForVersion(v.getId());
        }

        DocumentVersion version = new DocumentVersion(doc.getId(), spaceId, versionNo);
        version.setContentHash(hash);
        version.setParseStatus("parsing");
        version = versionRepo.save(version);

        List<Chunk> chunks = splitIntoChunks(content, version.getId(), spaceId, doc.getId());
        chunkRepo.saveAll(chunks);
        version.setChunkCount(chunks.size());
        version.setParseStatus("ready");
        versionRepo.save(version);

        indexToVectorStore(chunks, spaceId, doc.getId(), version.getId());

        doc.setStatus("ready");
        doc = docRepo.save(doc);

        log.info("Uploaded document '{}' to space '{}', version {}, chunks={}",
                filename, spaceId, versionNo, chunks.size());
        return doc;
    }

    public List<DocumentVersion> getDocumentVersions(String documentId) {
        return versionRepo.findByDocumentIdOrderByVersionNoDesc(documentId);
    }

    public String getDocumentSpaceId(String documentId) {
        return docRepo.findById(documentId)
                .map(Document::getSpaceId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));
    }

    public DocumentVersion getActiveVersion(String documentId) {
        return versionRepo.findByDocumentIdAndActiveTrue(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档没有活跃版本: " + documentId));
    }

    @Transactional
    public DocumentVersion rollbackToVersion(String documentId, int targetVersionNo) {
        List<DocumentVersion> all = versionRepo.findByDocumentIdOrderByVersionNoDesc(documentId);
        DocumentVersion target = versionRepo.findByDocumentIdAndVersionNo(documentId, targetVersionNo)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + targetVersionNo));

        int previousActiveNo = all.stream()
                .filter(DocumentVersion::isActive)
                .mapToInt(DocumentVersion::getVersionNo)
                .findFirst()
                .orElse(0);

        for (DocumentVersion v : all) {
            v.setActive(false);
            versionRepo.save(v);
            disableChunksForVersion(v.getId());
        }

        target.setActive(true);
        target.setRollbackFromVersion(previousActiveNo);
        versionRepo.save(target);
        enableChunksForVersion(target.getId());

        log.info("Rolled back document '{}' to version {}", documentId, targetVersionNo);
        return target;
    }

    public List<Chunk> searchChunks(String spaceId, String query, int topK) {
        try {
            Filter.Expression filterExpr = new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key("space_id"),
                    new Filter.Value(spaceId));

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(filterExpr)
                    .build();

            List<org.springframework.ai.document.Document> results = vectorStore.similaritySearch(request);
            if (results == null || results.isEmpty()) {
                // MySQL keeps chunks across restarts, while the in-process vector store
                // starts empty. Fall back to persisted chunks instead of returning no answer.
                return searchChunksFromDb(spaceId, query, topK);
            }
            return results.stream()
                    .map(doc -> chunkFromDocument(doc))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Vector store search failed, falling back to DB search: {}", e.getMessage());
            return searchChunksFromDb(spaceId, query, topK);
        }
    }

    private List<Chunk> searchChunksFromDb(String spaceId, String query, int topK) {
        List<Chunk> spaceChunks = chunkRepo.findBySpaceIdAndEnabledTrue(spaceId);
        String queryLower = query.toLowerCase();
        return spaceChunks.stream()
                .map(c -> Map.entry(c, scoreSimilarity(c.getContent().toLowerCase(), queryLower)))
                .filter(e -> e.getValue() >= MIN_SEARCH_SCORE)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Chunk chunkFromDocument(org.springframework.ai.document.Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String chunkId = meta.get("chunk_id") != null ? String.valueOf(meta.get("chunk_id")) : null;
        String versionId = meta.get("version_id") != null ? String.valueOf(meta.get("version_id")) : null;
        String spaceId = meta.get("space_id") != null ? String.valueOf(meta.get("space_id")) : null;
        String documentId = meta.get("document_id") != null ? String.valueOf(meta.get("document_id")) : null;
        Object headingObj = meta.get("heading");
        String heading = headingObj != null ? String.valueOf(headingObj) : null;
        Object chunkIndexObj = meta.get("chunk_index");
        int chunkIndex = 0;
        if (chunkIndexObj instanceof Number n) {
            chunkIndex = n.intValue();
        }

        if (versionId == null || spaceId == null || documentId == null) {
            return null;
        }

        Chunk chunk = new Chunk(versionId, spaceId, documentId, doc.getText(), chunkIndex);
        if (chunkId != null) chunk.setId(chunkId);
        if (heading != null) chunk.setHeading(heading);
        chunk.setEnabled(true);
        return chunk;
    }

    private String extractText(MultipartFile file, String filename) throws IOException {
        byte[] bytes = file.getBytes();
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            try {
                List<org.springframework.ai.document.Document> pages =
                        new PagePdfDocumentReader(new ByteArrayResource(bytes)).get();
                return pages.stream()
                        .map(org.springframework.ai.document.Document::getText)
                        .filter(text -> text != null && !text.isBlank())
                        .reduce((a, b) -> a + "\n\n" + b)
                        .orElse("");
            } catch (Exception e) {
                log.warn("PDF parse failed for '{}': {}", filename, e.getMessage());
                throw new IllegalArgumentException("PDF 解析失败，请确认文件未损坏");
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private double scoreSimilarity(String text, String query) {
        Set<String> textTokens = tokenize(text);
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || textTokens.isEmpty()) return 0;
        long matches = queryTokens.stream().filter(textTokens::contains).count();
        return (double) matches / Math.sqrt(textTokens.size() * queryTokens.size());
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String word : text.split("\\s+")) {
            if (word.isBlank()) continue;
            for (char c : word.toCharArray()) {
                if (isCjk(c)) {
                    tokens.add(String.valueOf(c));
                } else if (Character.isLetterOrDigit(c)) {
                    tokens.add(String.valueOf(Character.toLowerCase(c)));
                }
            }
        }
        return tokens;
    }

    private boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
    }

    private List<Chunk> splitIntoChunks(String text, String versionId, String spaceId, String docId) {
        List<Chunk> chunks = new ArrayList<>();
        if (text.length() <= CHUNK_SIZE) {
            chunks.add(new Chunk(versionId, spaceId, docId, text, 0));
            return chunks;
        }
        int start = 0;
        int idx = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            chunks.add(new Chunk(versionId, spaceId, docId, text.substring(start, end), idx));
            start += CHUNK_SIZE - CHUNK_OVERLAP;
            idx++;
        }
        return chunks;
    }

    private void indexToVectorStore(List<Chunk> chunks, String spaceId, String docId, String versionId) {
        try {
            List<org.springframework.ai.document.Document> aiDocs = new ArrayList<>();
            for (Chunk c : chunks) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("space_id", spaceId);
                meta.put("document_id", docId);
                meta.put("version_id", versionId);
                meta.put("chunk_id", c.getId() != null ? c.getId() : "");
                meta.put("chunk_index", c.getChunkIndex());
                if (c.getHeading() != null) meta.put("heading", c.getHeading());
                aiDocs.add(new org.springframework.ai.document.Document(c.getContent(), meta));
            }
            vectorStore.add(aiDocs);
        } catch (Exception e) {
            log.warn("Vector store indexing failed (non-fatal): {}", e.getMessage());
        }
    }

    private void disableChunksForVersion(String versionId) {
        List<Chunk> chunks = chunkRepo.findByVersionId(versionId);
        for (Chunk c : chunks) {
            c.setEnabled(false);
        }
        chunkRepo.saveAll(chunks);

        // Delete vectors for this version from vector store
        try {
            Filter.Expression filterExpr = new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key("version_id"),
                    new Filter.Value(versionId));
            vectorStore.delete(filterExpr);
        } catch (Exception e) {
            log.warn("Failed to delete version vectors from vector store: {}", e.getMessage());
        }
    }

    private void enableChunksForVersion(String versionId) {
        List<Chunk> chunks = chunkRepo.findByVersionId(versionId);
        for (Chunk c : chunks) {
            c.setEnabled(true);
        }
        chunkRepo.saveAll(chunks);

        // Re-add vectors for this version to vector store
        if (!chunks.isEmpty()) {
            Chunk first = chunks.get(0);
            indexToVectorStore(chunks, first.getSpaceId(), first.getDocumentId(), versionId);
        }
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
