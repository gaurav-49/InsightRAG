package com.insightrag.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.insightrag.config.InsightRagProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    @Bean
    GenerationProvider generationProvider(InsightRagProperties props, ObjectMapper json) {
        return switch (props.llm().provider().toLowerCase()) {
            case "anthropic" -> new AnthropicGenerationProvider(props.llm());
            case "ollama" -> new OllamaGenerationProvider(props.llm(), json);
            case "extractive" -> new ExtractiveGenerationProvider();
            default -> throw new IllegalStateException("Unknown insightrag.llm.provider " + props.llm().provider());
        };
    }
}
