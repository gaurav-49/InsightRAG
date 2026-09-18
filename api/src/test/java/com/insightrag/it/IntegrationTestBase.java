package com.insightrag.it;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full application against real Postgres + pgvector and Redis. Flyway applies the same
 * db/migration scripts the deployment uses (NFR-09): there is no second schema definition.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    static final Path BLOBS;

    static {
        try {
            BLOBS = Files.createTempDirectory("insightrag-blobs");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        r.add("spring.data.redis.url", Containers::redisUrl);
        r.add("insightrag.upload.blob-root", BLOBS::toString);
        r.add("insightrag.retrieval.floor", () -> "0.15");
        r.add("insightrag.retrieval.relative-floor", () -> "0.7");
        r.add("insightrag.llm.provider", () -> "extractive");
        r.add("insightrag.embedding.provider", () -> "hash");
        r.add("logging.structured.format.console", () -> "");
    }
}
