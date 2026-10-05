package com.intellidesk.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey key;
    private final long accessTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes, current length: " + keyBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiration = accessTokenExpiration;
    }

    public String generateAccessToken(Long userId, String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("userId", userId)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        return parseAccessToken(token) != null;
    }

    /** Validate and extract once; authorities are resolved from the current database state. */
    public AccessIdentity parseAccessToken(String token) {
        try {
            Claims claims = parseToken(token);
            Long userId = claims.get("userId", Long.class);
            String username = claims.getSubject();
            if (!"access".equals(claims.get("type", String.class)) || userId == null || userId <= 0
                    || username == null || username.isBlank() || claims.getExpiration() == null) {
                return null;
            }
            return new AccessIdentity(userId, username);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected access JWT: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    public record AccessIdentity(Long userId, String username) { }

    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    public Long getUserIdFromToken(String token) {
        return parseToken(token).get("userId", Long.class);
    }
}
