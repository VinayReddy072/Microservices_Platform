package com.emergencylending.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey signingKey;

    /**
     * Derives the HMAC-SHA256 signing key from the Base64-encoded JWT_SECRET env var.
     * Spring will fail to start if JWT_SECRET is not set — there is no default, because
     * a missing secret should be an explicit startup error, not a silent security hole.
     *
     * @throws IllegalArgumentException if the decoded secret is shorter than 256 bits (32 bytes)
     */
    public JwtUtil(@Value("${jwt.secret}") String base64Secret) {
        byte[] keyBytes = Decoders.BASE64.decode(base64Secret);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT_SECRET must decode to at least 256 bits (32 bytes). " +
                    "Generate one with: openssl rand -base64 32");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + 3_600_000))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parse and validate a JWT. Throws a {@link io.jsonwebtoken.JwtException} subclass
     * (ExpiredJwtException, MalformedJwtException, SignatureException, etc.) if the
     * token is invalid — callers should catch the base type.
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
