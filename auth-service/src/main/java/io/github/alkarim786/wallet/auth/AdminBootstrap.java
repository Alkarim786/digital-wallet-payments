package io.github.alkarim786.wallet.auth;

import io.github.alkarim786.wallet.common.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the bootstrap admin from configuration if it does not exist yet (idempotent across restarts). */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          @Value("${wallet.admin.email:}") String email, @Value("${wallet.admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.length() < 8) {
            return;
        }
        if (users.findByEmail(email).isPresent()) {
            return;
        }
        try {
            users.insert(email, encoder.encode(password), Role.ADMIN);
            log.info("bootstrap admin {} created", email);
        } catch (DuplicateKeyException race) {
            // another replica won the race
        }
    }
}
