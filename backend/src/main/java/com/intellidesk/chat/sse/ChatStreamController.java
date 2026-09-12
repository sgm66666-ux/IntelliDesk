package com.intellidesk.chat.sse;

import com.intellidesk.chat.dto.ChatRequest;
import com.intellidesk.chat.orchestration.ChatOrchestrationService;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversations/{conversationId}")
public class ChatStreamController {

    private final ChatOrchestrationService orchestrationService;
    private final UserService userService;

    public ChatStreamController(ChatOrchestrationService orchestrationService, UserService userService) {
        this.orchestrationService = orchestrationService;
        this.userService = userService;
    }

    @PostMapping("/messages/stream")
    @PreAuthorize("hasAuthority('conversation:view')")
    public SseEmitter streamChat(@PathVariable Long workspaceId,
                                  @PathVariable Long conversationId,
                                  @Valid @RequestBody ChatRequest request,
                                  Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return orchestrationService.chat(request, workspaceId, conversationId, user.getId());
    }
}