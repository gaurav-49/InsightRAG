package com.insightrag.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared, lazily started containers: the same images the Compose stack runs. */
public final class Containers {

    public static final DockerImageName PGVECTOR = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR)
            .withDatabaseName("insightrag").withUsername("insightrag").withPassword("insightrag");

    public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private Containers() {
    }

    public static String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0";
    }
}
