package com.intellidesk.api_key;

import com.intellidesk.api_key.dto.ApiKeyResponse;
import com.intellidesk.api_key.dto.CreateApiKeyRequest;
import com.intellidesk.api_key.dto.CreateApiKeyResponse;
import com.intellidesk.common.Result;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserService userService;

    public ApiKeyController(ApiKeyService apiKeyService, UserService userService) {
        this.apiKeyService = apiKeyService;
        this.userService = userService;
    }

    @PostMapping
    public Result<CreateApiKeyResponse> create(@PathVariable Long workspaceId,
                                                @Valid @RequestBody CreateApiKeyRequest request,
                                                Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(apiKeyService.create(workspaceId, user.getId(), request));
    }

    @GetMapping
    public Result<List<ApiKeyResponse>> list(@PathVariable Long workspaceId,
                                              Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(apiKeyService.list(workspaceId, user.getId()));
    }

    @GetMapping("/{keyId}")
    public Result<ApiKeyResponse> detail(@PathVariable Long workspaceId,
                                          @PathVariable Long keyId,
                                          Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return Result.success(apiKeyService.detail(workspaceId, keyId, user.getId()));
    }

    @DeleteMapping("/{keyId}")
    public Result<Void> revoke(@PathVariable Long workspaceId,
                                @PathVariable Long keyId,
                                Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        apiKeyService.revoke(workspaceId, keyId, user.getId());
        return Result.success();
    }
}