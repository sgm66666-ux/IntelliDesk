package com.intellidesk.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

@Component
public class CookieUtil {

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final int MAX_AGE_SECONDS = 604800;
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthCookieProperties authCookieProperties;

    public CookieUtil(AuthCookieProperties authCookieProperties) {
        this.authCookieProperties = authCookieProperties;
    }

    public void setRefreshTokenCookie(HttpServletResponse response, String token) {
        Cookie cookie = createRefreshTokenCookie(token);
        cookie.setMaxAge(MAX_AGE_SECONDS);
        response.addCookie(cookie);
    }

    public void clearRefreshTokenCookie(HttpServletResponse response) {
        Cookie cookie = createRefreshTokenCookie("");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private Cookie createRefreshTokenCookie(String value) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(authCookieProperties.isSecure());
        cookie.setPath(COOKIE_PATH);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    public static String extractRefreshTokenFromCookies(jakarta.servlet.http.Cookie[] cookies) {
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (REFRESH_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}