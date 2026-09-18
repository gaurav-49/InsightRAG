package com.insightrag.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.insightrag.config.InsightRagProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    EmbeddingProvider embeddingProvider(InsightRagProperties props, ObjectMapper json) {
        InsightRagProperties.Embedding e = props.embedding();
        return switch (e.provider().toLowerCase()) {
            case "hash" -> new HashEmbeddingProvider();
            case "ollama" -> HttpEmbeddingProvider.ollama(e.baseUrl(), e.model(), e.timeout(), e.maxAttempts(), json);
            case "openai" -> HttpEmbeddingProvider.openAi(e.baseUrl(), e.model(), e.apiKey(), e.timeout(), e.maxAttempts(), json);
            default -> throw new IllegalStateException("Unknown insightrag.embedding.provider " + e.provider());
        };
    }
}
