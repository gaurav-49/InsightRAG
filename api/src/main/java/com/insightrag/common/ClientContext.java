package com.insightrag.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identity of the authenticated caller, derived from the JWT subject. */
public record ClientContext(String clientId, UUID userId) {

    public static ClientContext from(Authentication auth) {
        String subject = auth.getName();
        if (auth.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            subject = jwt.getSubject();
        }
        return new ClientContext(subject, toUuid(subject));
    }

    /** documents.uploaded_by is a UUID; non-UUID subjects map to a stable name-based UUID. */
    static UUID toUuid(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
        }
    }
}
