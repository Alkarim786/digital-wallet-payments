package io.github.alkarim786.wallet.auth;

import io.github.alkarim786.wallet.common.JwtService;
import io.github.alkarim786.wallet.common.Role;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "wallet.jwt.secret=0123456789abcdef0123456789abcdef",
        "wallet.admin.email=root@wallet.test",
        "wallet.admin.password=root-password-1"})
class AuthIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;

    private Map<String, Object> creds(String email, String pw) {
        return Map.of("email", email, "password", pw);
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerLoginAndTokenCarriesIdentity() {
        var reg = rest.postForEntity("/auth/register", creds("Alice@Example.com", "password-123"), Map.class);
        assertEquals(HttpStatus.CREATED, reg.getStatusCode());
        var login = rest.postForEntity("/auth/login", creds("alice@example.com", "password-123"), Map.class); // case-insensitive
        assertEquals(HttpStatus.OK, login.getStatusCode());
        var id = jwt.verify((String) login.getBody().get("accessToken")).orElseThrow();
        assertEquals(Role.USER, id.role());
        assertEquals("Alice@Example.com", id.email());

        var headers = new HttpHeaders();
        headers.add("X-User-Id", String.valueOf(id.userId()));
        var me = rest.exchange("/auth/me", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals("Alice@Example.com", me.getBody().get("email"));
    }

    @Test
    void duplicateEmailIsConflictEvenWithDifferentCase() {
        rest.postForEntity("/auth/register", creds("dup@example.com", "password-123"), Map.class);
        assertEquals(HttpStatus.CONFLICT, rest.postForEntity("/auth/register", creds("DUP@example.com", "password-123"), Map.class).getStatusCode());
    }

    @Test
    void badCredentialsAreRejectedUniformly() {
        rest.postForEntity("/auth/register", creds("bob@example.com", "password-123"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, rest.postForEntity("/auth/login", creds("bob@example.com", "wrong-password"), Map.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, rest.postForEntity("/auth/login", creds("nobody@example.com", "password-123"), Map.class).getStatusCode());
    }

    @Test
    void validationRejectsShortPasswordAndBadEmail() {
        assertEquals(HttpStatus.BAD_REQUEST, rest.postForEntity("/auth/register", creds("c@example.com", "short"), Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, rest.postForEntity("/auth/register", creds("not-an-email", "password-123"), Map.class).getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void selfRegistrationCannotCreateAdminButBootstrapAdminCanLogin() {
        var body = Map.of("email", "evil@example.com", "password", "password-123", "role", "ADMIN");
        var reg = rest.postForEntity("/auth/register", body, Map.class);
        assertEquals(Role.USER.name(), reg.getBody().get("role"));
        var admin = rest.postForEntity("/auth/login", creds("root@wallet.test", "root-password-1"), Map.class);
        assertEquals(HttpStatus.OK, admin.getStatusCode());
        assertEquals("ADMIN", admin.getBody().get("role"));
    }
}
