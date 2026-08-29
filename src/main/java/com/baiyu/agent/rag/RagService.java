package com.baiyu.agent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    @Autowired
    public RagService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    public String addDocument(String content, String metadata) {
        Document doc = new Document(content);
        vectorStore.add(List.of(doc));
        return "Document added successfully";
    }

    public String addTextFile(String filename, String content) {
        Document doc = new Document(content,
                java.util.Map.of("filename", filename, "source", "upload"));
        vectorStore.add(List.of(doc));
        return "File '" + filename + "' added to knowledge base";
    }

    public List<String> search(String query, int topK) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());
        return results.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
    }

    public String queryWithContext(String question) {
        List<Document> relevant = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(3).build());

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
}
