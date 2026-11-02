package io.github.alkarim786.wallet.common;

/** Identity headers set by the gateway after it verifies the JWT. Internal services trust only these. */
public final class Headers {
    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String COUNTRY = "X-Country";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private Headers() {}
}
