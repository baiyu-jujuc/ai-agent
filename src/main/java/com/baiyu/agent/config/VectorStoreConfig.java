package com.baiyu.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Bean
    @Primary
    @ConditionalOnProperty(name = "agent.storage.vector-store", havingValue = "memory", matchIfMissing = true)
    public VectorStore inMemoryVectorStore(EmbeddingModel embeddingModel) {
        return new EmbeddingBasedVectorStore(embeddingModel);
    }

    public static class EmbeddingBasedVectorStore implements VectorStore {

        private final EmbeddingModel embeddingModel;
        private final Map<String, DocumentEntry> store = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        private record DocumentEntry(Document document, float[] embedding) {}

        public EmbeddingBasedVectorStore(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        @Override
        public void add(List<Document> documents) {
            for (Document doc : documents) {
                String id = doc.getId() != null ? doc.getId() : String.valueOf(idGen.incrementAndGet());
                float[] embedding = embeddingModel.embed(doc.getText());
                store.put(id, new DocumentEntry(doc, embedding));
            }
            log.info("Added {} documents to vector store (total: {})", documents.size(), store.size());
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(store::remove);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException("Filter-based delete not supported");
        }

        @Override
        public List<Document> similaritySearch(String query) {
            return similaritySearch(SearchRequest.builder().query(query).topK(5).similarityThreshold(0.5).build());
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            if (store.isEmpty()) return Collections.emptyList();

            float[] queryEmbedding = embeddingModel.embed(request.getQuery());

            List<ScoredDoc> scored = new ArrayList<>();
            for (Map.Entry<String, DocumentEntry> entry : store.entrySet()) {
                double score = cosineSimilarity(queryEmbedding, entry.getValue().embedding());
                scored.add(new ScoredDoc(entry.getValue().document(), score));
            }

            double threshold = request.getSimilarityThreshold();
            scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());

            return scored.stream()
                    .filter(s -> s.score() >= threshold)
                    .limit(request.getTopK())
                    .map(ScoredDoc::document)
                    .toList();
        }

        private record ScoredDoc(Document document, double score) {}

        private double cosineSimilarity(float[] a, float[] b) {
            if (a.length != b.length) return 0;
            double dot = 0, normA = 0, normB = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            if (normA == 0 || normB == 0) return 0;
            return dot / (Math.sqrt(normA) * Math.sqrt(normB));
        }
    }
}
