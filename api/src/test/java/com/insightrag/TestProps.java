package com.insightrag;

import java.util.Map;

import com.insightrag.config.InsightRagProperties;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** InsightRagProperties with @DefaultValue defaults applied, plus overrides. */
public final class TestProps {

    private TestProps() {
    }

    public static InsightRagProperties of(Map<String, String> overrides) {
        return new Binder(new MapConfigurationPropertySource(overrides))
                .bindOrCreate("insightrag", InsightRagProperties.class);
    }

    public static InsightRagProperties defaults() {
        return of(Map.of());
    }
}
