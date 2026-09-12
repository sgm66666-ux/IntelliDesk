package com.intellidesk.auth;

import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.LoginResponse;
import com.intellidesk.auth.dto.RefreshTokenRequest;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserService userService, JwtTokenProvider jwtTokenProvider,
                       RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        User user = userService.register(
                request.getUsername(),
                request.getPassword(),
                request.getEmail(),
                request.getNickname());
        return generateTokens(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userService.findByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(ErrorCode.USERNAME_OR_PASSWORD_ERROR);
        }
        if (!userService.verifyPassword(user, request.getPassword())) {
            throw new BusinessException(ErrorCode.USERNAME_OR_PASSWORD_ERROR);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return generateTokens(user);
    }

    public LoginResponse refresh(RefreshTokenRequest request) {
        String refreshTokenId = request.getRefreshToken();
        Long userId = refreshTokenService.consumeRefreshToken(refreshTokenId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        return generateTokens(user);
    }

    public void logout(String refreshTokenId) {
        refreshTokenService.revokeRefreshToken(refreshTokenId);
    }

    private LoginResponse generateTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = refreshTokenService.createRefreshToken(user.getId());
        return new LoginResponse(accessToken, refreshToken, user.getId(), user.getUsername());
    }
}