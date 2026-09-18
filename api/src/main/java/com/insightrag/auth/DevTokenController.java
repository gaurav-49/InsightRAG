package com.insightrag.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.insightrag.common.ApiException;
import com.insightrag.config.InsightRagProperties;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only token minting (disabled unless insightrag.auth.dev-token-enabled=true).
 * Production deployments put a real identity provider in front and share only the signing key
 * or switch the resource server to the provider's JWKS.
 */
@RestController
public class DevTokenController {

    private static final Set<String> ALLOWED_SCOPES = Set.of("query", "admin");

    private final JwtEncoder encoder;
    private final InsightRagProperties props;

    public DevTokenController(JwtEncoder encoder, InsightRagProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public record TokenRequest(String subject, List<String> scopes, Long ttlSeconds) {
    }

    @PostMapping("/api/v1/auth/dev-token")
    public Map<String, Object> mint(@RequestBody(required = false) TokenRequest request) {
        if (!props.auth().devTokenEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "Not found");
        }
        TokenRequest r = request == null ? new TokenRequest(null, null, null) : request;
        List<String> scopes = r.scopes() == null || r.scopes().isEmpty() ? List.of("query") : r.scopes();
        if (!ALLOWED_SCOPES.containsAll(scopes)) {
            throw ApiException.badRequest("invalid_scope", "Scopes must be drawn from " + ALLOWED_SCOPES);
        }
        String subject = r.subject() == null || r.subject().isBlank() ? UUID.randomUUID().toString() : r.subject();
        long ttl = r.ttlSeconds() == null ? 3600 : Math.min(Math.max(r.ttlSeconds(), 60), 86_400);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.auth().issuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofSeconds(ttl)))
                .claim("scope", String.join(" ", scopes))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", ttl, "subject", subject, "scopes", scopes);
    }
}
