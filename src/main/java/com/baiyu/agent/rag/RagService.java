package com.baiyu.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 100;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    @Value("${agent.storage.vector-store:memory}")
    private String vectorStoreType;

    @Autowired
    public RagService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    public String addDocument(String content, String metadata) {
        List<Document> chunks = chunkDocument(content, Map.of(
                "source", "manual",
                "metadata", metadata,
                "timestamp", Instant.now().toString()
        ));
        try {
            vectorStore.add(chunks);
            return "Document added (" + chunks.size() + " chunks) to " + getStoreTypeName();
        } catch (Exception e) {
            log.error("Failed to add document: {}", e.getMessage());
            return "Failed to add document: " + e.getMessage();
        }
    }

    public String addTextFile(String filename, String content) {
        List<Document> chunks = chunkDocument(content, Map.of(
                "filename", filename,
                "source", "upload",
                "timestamp", Instant.now().toString()
        ));
        try {
            vectorStore.add(chunks);
            return "File '" + filename + "' added (" + chunks.size() + " chunks) to " + getStoreTypeName();
        } catch (Exception e) {
            log.error("Failed to add file: {}", e.getMessage());
            return "Failed to add file: " + e.getMessage();
        }
    }

    private List<Document> chunkDocument(String text, Map<String, Object> metadata) {
        List<Document> chunks = new ArrayList<>();
        if (text.length() <= CHUNK_SIZE) {
            chunks.add(new Document(text, metadata));
            return chunks;
        }

        int start = 0;
        int chunkIndex = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunkText = text.substring(start, end);
            Map<String, Object> chunkMeta = new java.util.HashMap<>(metadata);
            chunkMeta.put("chunkIndex", chunkIndex);
            chunkMeta.put("totalChunks", (text.length() + CHUNK_SIZE - 1) / CHUNK_SIZE);
            chunks.add(new Document(chunkText, chunkMeta));
            start += CHUNK_SIZE - CHUNK_OVERLAP;
            chunkIndex++;
        }
        log.info("Split document into {} chunks (size={} overlap={})", chunks.size(), CHUNK_SIZE, CHUNK_OVERLAP);
        return chunks;
    }

    public List<String> search(String query, int topK) {
        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.5)
                            .build());
            return results.stream()
                    .map(Document::getText)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public String queryWithContext(String question) {
        List<Document> relevant;
        try {
            relevant = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(3)
                            .similarityThreshold(0.5)
                            .build());
        } catch (Exception e) {
            log.warn("Similarity search failed: {}", e.getMessage());
            relevant = List.of();
        }

        String context = relevant.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        if (context.isEmpty()) {
            return chatClient.prompt()
                    .user(question)
                    .call()
                    .content();
        }

        String augmentedPrompt = """
                基于以下上下文回答问题。如果上下文中没有相关信息，请说明并基于你的知识回答。
                
                上下文:
                %s
                
                问题: %s
                
                回答:""".formatted(context, question);

        return chatClient.prompt()
                .user(augmentedPrompt)
                .call()
                .content();
    }

    public String uploadAndIndex(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return addTextFile(file.getOriginalFilename(), content);
    }

    public String getVectorStoreType() {
        return vectorStoreType;
    }

    public String getStoreTypeName() {
        return "qdrant".equals(vectorStoreType) ? "Qdrant" : "InMemory (embedding-based)";
    }

    public int getDocumentCount() {
        try {
            List<String> results = search("", 1000);
            return results.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
