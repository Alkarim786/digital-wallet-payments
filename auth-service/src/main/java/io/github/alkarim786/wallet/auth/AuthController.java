package io.github.alkarim786.wallet.auth;

import io.github.alkarim786.wallet.common.Headers;
import io.github.alkarim786.wallet.common.JwtService;
import io.github.alkarim786.wallet.common.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    public record Credentials(@NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(min = 8, max = 72) String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, long userId, String email, Role role) {}

    public record Me(long userId, String email, Role role) {}

    // A hash of a random password, verified when the email is unknown so that response time does not leak
    // which emails are registered.
    private final String dummyHash;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody Credentials c) {
        try {
            // Self-registration only ever creates USER accounts; ADMIN comes from the bootstrap config.
            var user = users.insert(c.email().trim(), encoder.encode(c.password()), Role.USER);
            return token(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody Credentials c) {
        var user = users.findByEmail(c.email().trim());
        boolean ok = encoder.matches(c.password(), user.map(UserRepository.User::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid email or password");
        }
        return token(user.get());
    }

    /** The gateway validates the JWT and injects the identity headers; this just echoes them from the database. */
    @GetMapping("/me")
    public Me me(@RequestHeader(Headers.USER_ID) long userId) {
        var u = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));
        return new Me(u.id(), u.email(), u.role());
    }

    private TokenResponse token(UserRepository.User u) {
        String t = jwt.issue(new JwtService.Identity(u.id(), u.email(), u.role()));
        return new TokenResponse(t, "Bearer", jwt.ttl().toSeconds(), u.id(), u.email(), u.role());
    }
}
