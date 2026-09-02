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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

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
        Document doc = new Document(content, Map.of(
                "source", "manual",
                "metadata", metadata,
                "timestamp", Instant.now().toString()
        ));
        try {
            vectorStore.add(List.of(doc));
            return "Document added successfully to " + getStoreTypeName();
        } catch (Exception e) {
            log.error("Failed to add document to vector store: {}", e.getMessage());
            return "Failed to add document: " + e.getMessage();
        }
    }

    public String addTextFile(String filename, String content) {
        Document doc = new Document(content, Map.of(
                "filename", filename,
                "source", "upload",
                "timestamp", Instant.now().toString()
        ));
        try {
            vectorStore.add(List.of(doc));
            return "File '" + filename + "' added to knowledge base (" + getStoreTypeName() + ")";
        } catch (Exception e) {
            log.error("Failed to add file to vector store: {}", e.getMessage());
            return "Failed to add file: " + e.getMessage();
        }
    }

    public List<String> search(String query, int topK) {
        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK).build());
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
                    SearchRequest.builder().query(question).topK(3).build());
        } catch (Exception e) {
            log.warn("Similarity search failed, answering without context: {}", e.getMessage());
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
                Based on the following context, answer the question.
                
                Context:
                %s
                
                Question: %s
                
                Answer:""".formatted(context, question);

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
        return "qdrant".equals(vectorStoreType) ? "Qdrant" : "InMemory";
    }

    public int getDocumentCount() {
        try {
            List<String> results = search("test", 1000);
            return results.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
