package com.intellidesk.agent;

import com.intellidesk.chat.dto.AgentChatRequest;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversations/{conversationId}")
public class AgentStreamController {

    private final AgentOrchestrationService agentOrchestrationService;
    private final UserService userService;

    public AgentStreamController(AgentOrchestrationService agentOrchestrationService,
                                  UserService userService) {
        this.agentOrchestrationService = agentOrchestrationService;
        this.userService = userService;
    }

    @PostMapping("/agent/stream")
    @PreAuthorize("hasAuthority('conversation:view')")
    public SseEmitter streamAgent(@PathVariable Long workspaceId,
                                   @PathVariable Long conversationId,
                                   @Valid @RequestBody AgentChatRequest request,
                                   Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        return agentOrchestrationService.chat(request, workspaceId, conversationId, user.getId());
    }
}