package com.emergencylending.gateway.auth;

import com.emergencylending.gateway.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Issues JWTs in exchange for valid credentials. Backed by a fixed in-memory
 * user list — a database-backed user service is out of scope for this phase.
 *
 * <p>Two accounts are pre-configured:
 * <ul>
 *   <li>{@code user} / {@code password}   — role USER (read-only access)</li>
 *   <li>{@code admin} / {@code adminpass} — role ADMIN (full access)</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final List<InMemoryUser> USERS = List.of(
            new InMemoryUser("user",  "password",  "USER"),
            new InMemoryUser("admin", "adminpass", "ADMIN")
    );

    private record InMemoryUser(String username, String password, String role) {}

    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@RequestBody LoginRequest request) {
        return USERS.stream()
                .filter(u -> u.username().equals(request.username())
                          && u.password().equals(request.password()))
                .findFirst()
                .map(u -> ResponseEntity.ok(new LoginResponse(
                        jwtUtil.generateToken(u.username(), u.role()))))
                .map(Mono::just)
                .orElse(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()));
    }
}
