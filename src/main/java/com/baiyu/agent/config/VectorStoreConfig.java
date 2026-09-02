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
            return store.values().stream()
                    .sorted((a, b) -> {
                        double scoreA = cosineSim(a.getText().toLowerCase(), query);
                        double scoreB = cosineSim(b.getText().toLowerCase(), query);
                        return Double.compare(scoreB, scoreA);
                    })
                    .limit(request.getTopK())
                    .toList();
        }

        private double cosineSim(String text, String query) {
            Set<String> textWords = new HashSet<>(Arrays.asList(text.split("\\s+")));
            Set<String> queryWords = new HashSet<>(Arrays.asList(query.split("\\s+")));
            long matches = queryWords.stream().filter(textWords::contains).count();
            if (queryWords.isEmpty()) return 0;
            return (double) matches / Math.sqrt(textWords.size() * queryWords.size());
        }
    }
}
