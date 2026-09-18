package com.insightrag.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * NFR-06: every business endpoint is JWT-authenticated (HS256). Scopes map to the personas of
 * §2.1: {@code query} for knowledge workers, {@code admin} for corpus administrators and
 * quality owners. Health and metrics are open for probes and the Prometheus scraper.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String QUERY = "SCOPE_query";
    static final String ADMIN = "SCOPE_admin";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/health", "/api/v1/metrics", "/api/v1/auth/dev-token", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/app.css", "/favicon.svg").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/query", "/api/v1/query/stream").hasAnyAuthority(QUERY, ADMIN)
                .requestMatchers(HttpMethod.GET, "/api/v1/documents", "/api/v1/documents/**").hasAnyAuthority(QUERY, ADMIN)
                .requestMatchers("/api/v1/documents", "/api/v1/documents/**").hasAuthority(ADMIN)
                .requestMatchers("/api/v1/eval/**").hasAuthority(ADMIN)
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    SecretKey jwtKey(InsightRagProperties props) {
        byte[] secret = props.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("insightrag.auth.jwt-secret must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, InsightRagProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.auth().issuer()));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }
}
