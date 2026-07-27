package org.zipp.ai.trigger.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/** HTTP adapter for the anonymous capability cookie. The raw secret is never exposed to JavaScript. */
@Component
public class AnonymousWorkspaceCookie {

    public static final String NAME = "ZIPP_ANON_WORKSPACE";
    private static final Duration LIFETIME = Duration.ofDays(365);

    private final boolean secure;

    public AnonymousWorkspaceCookie(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    public void write(HttpServletResponse response, String rawCredential) {
        // SameSite=Lax limits ambient cross-site use; HttpOnly keeps the capability out of JS.
        ResponseCookie cookie = ResponseCookie.from(NAME, rawCredential)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(LIFETIME)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
