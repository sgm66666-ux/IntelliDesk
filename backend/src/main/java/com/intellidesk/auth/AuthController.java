package com.intellidesk.auth;

import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.LoginResponse;
import com.intellidesk.auth.dto.RefreshTokenRequest;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CookieUtil cookieUtil;

    public AuthController(AuthService authService, CookieUtil cookieUtil) {
        this.authService = authService;
        this.cookieUtil = cookieUtil;
    }

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletResponse response) {
        LoginResponse result = authService.register(request);
        cookieUtil.setRefreshTokenCookie(response, result.getRefreshToken());
        return Result.success(result);
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletResponse response) {
        LoginResponse loginResponse = authService.login(request);
        cookieUtil.setRefreshTokenCookie(response, loginResponse.getRefreshToken());
        return Result.success(loginResponse);
    }

    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestBody(required = false) RefreshTokenRequest request,
                                         @CookieValue(value = "refresh_token", required = false) String cookieRefreshToken,
                                         HttpServletResponse response) {
        String tokenToRefresh = cookieRefreshToken != null
                ? cookieRefreshToken
                : request != null ? request.getRefreshToken() : null;
        RefreshTokenRequest effectiveRequest = new RefreshTokenRequest();
        effectiveRequest.setRefreshToken(tokenToRefresh);
        LoginResponse result = authService.refresh(effectiveRequest);
        cookieUtil.setRefreshTokenCookie(response, result.getRefreshToken());
        return Result.success(result);
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) Map<String, String> body,
                               @CookieValue(value = "refresh_token", required = false) String cookieRefreshToken,
                               HttpServletResponse response) {
        String tokenToLogout = cookieRefreshToken != null
                ? cookieRefreshToken
                : body != null ? body.get("refreshToken") : null;
        if (tokenToLogout != null) {
            authService.logout(tokenToLogout);
        }
        cookieUtil.clearRefreshTokenCookie(response);
        return Result.success();
    }
}