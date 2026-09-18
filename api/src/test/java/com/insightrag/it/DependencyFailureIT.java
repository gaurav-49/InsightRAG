package com.insightrag.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/** §5.7 failure table, rows "Cache unavailable" and "Vector store unavailable". */
class DependencyFailureIT {

    static Path tempDir() {
        try {
            return Files.createTempDirectory("insightrag-blobs");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Redis is an optimisation, never a dependency for correctness. */
    @Nested
    @Tag("integration")
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RedisDown {

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
            r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
            r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
            r.add("spring.data.redis.url", () -> "redis://127.0.0.1:1/0"); // nothing listens here
            r.add("insightrag.upload.blob-root", () -> tempDir().toString());
            r.add("insightrag.retrieval.floor", () -> "0.15");
            r.add("logging.structured.format.console", () -> "");
        }

        @Autowired
        MockMvc mvc;
        @Autowired
        JdbcTemplate jdbc;

        @Test
        @Order(1)
        void uploadIsStillAcceptedAndStaysQueuedForTheSweeper() throws Exception {
            mvc.perform(multipart("/api/v1/documents")
                            .file(new MockMultipartFile("file", "redis-down.txt", "text/plain",
                                    ("Unique text for the redis outage test " + System.nanoTime()).getBytes(StandardCharsets.UTF_8)))
                            .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_admin"))))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("QUEUED"));
        }

        @Test
        @Order(2)
        void queriesAreStillAnsweredWithoutCaching() throws Exception {
            mvc.perform(post("/api/v1/query").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_query")))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"anything about gearboxes?\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cacheTier").value("MISS"));
        }

        @Test
        @Order(3)
        void healthReportsDegradedNotDown() throws Exception {
            mvc.perform(get("/api/v1/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEGRADED"))
                    .andExpect(jsonPath("$.dependencies.redis.status").value("DOWN"));
        }
    }

    /** Fail fast with 503; never call the LLM without context. */
    @Nested
    @Tag("integration")
    @SpringBootTest
    @AutoConfigureMockMvc
    class PostgresDown {

        static final PostgreSQLContainer<?> DOOMED = new PostgreSQLContainer<>(Containers.PGVECTOR)
                .withDatabaseName("insightrag").withUsername("insightrag").withPassword("insightrag");

        static {
            DOOMED.start();
        }

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            r.add("spring.datasource.url", DOOMED::getJdbcUrl);
            r.add("spring.datasource.username", DOOMED::getUsername);
            r.add("spring.datasource.password", DOOMED::getPassword);
            r.add("spring.data.redis.url", Containers::redisUrl);
            r.add("insightrag.upload.blob-root", () -> tempDir().toString());
            r.add("logging.structured.format.console", () -> "");
        }

        @Autowired
        MockMvc mvc;

        @AfterAll
        static void cleanup() {
            if (DOOMED.isRunning()) {
                DOOMED.stop();
            }
        }

        @Test
        void queryFailsFastWith503AndHealthIsDown() throws Exception {
            DOOMED.stop();
            long started = System.nanoTime();
            mvc.perform(post("/api/v1/query").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_query")))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"what is the notice period?\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("store_unavailable"));
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(10_000);
            mvc.perform(get("/api/v1/health")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"));
        }
    }
}
