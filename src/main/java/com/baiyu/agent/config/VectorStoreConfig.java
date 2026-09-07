package com.baiyu.agent.config;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class VectorStoreConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "agent.storage.vector-store", havingValue = "memory", matchIfMissing = true)
    public VectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }

    public static class InMemoryVectorStore implements VectorStore {

        private final Map<String, Document> store = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        @Override
        public void add(List<Document> documents) {
            for (Document doc : documents) {
                String id = String.valueOf(idGen.incrementAndGet());
                store.put(id, doc);
            }
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(store::remove);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException("Filter-based delete not supported in InMemoryVectorStore");
        }

        @Override
        public List<Document> similaritySearch(String query) {
            return similaritySearch(SearchRequest.builder().query(query).topK(5).build());
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            if (store.isEmpty()) return Collections.emptyList();
            String query = request.getQuery().toLowerCase();
            double threshold = request.getSimilarityThreshold();
            int topK = request.getTopK();
            return store.values().stream()
                    .map(doc -> {
                        double score = cosineSim(doc.getText().toLowerCase(), query);
                        return Map.entry(doc, score);
                    })
                    .filter(e -> threshold <= 0 || e.getValue() >= threshold)
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(topK)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        private double cosineSim(String text, String query) {
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
                if (containsCjk(word)) {
                    for (char c : word.toCharArray()) {
                        if (isCjkChar(c)) {
                            tokens.add(String.valueOf(c));
                        } else if (Character.isLetterOrDigit(c)) {
                            tokens.add(String.valueOf(Character.toLowerCase(c)));
                        }
                    }
                } else {
                    tokens.add(word.toLowerCase());
                }
            }
            return tokens;
        }

        private boolean containsCjk(String s) {
            for (char c : s.toCharArray()) {
                if (isCjkChar(c)) return true;
            }
            return false;
        }

        private boolean isCjkChar(char c) {
            return (c >= '\u4E00' && c <= '\u9FFF')
                    || (c >= '\u3400' && c <= '\u4DBF')
                    || (c >= '\uF900' && c <= '\uFAFF');
        }
    }
}
