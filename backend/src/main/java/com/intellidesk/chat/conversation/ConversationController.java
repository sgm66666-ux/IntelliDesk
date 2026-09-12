package com.intellidesk.chat.conversation;

import com.intellidesk.chat.dto.ConversationCreateRequest;
import com.intellidesk.chat.dto.ConversationUpdateRequest;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.common.Result;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final UserService userService;

    public ConversationController(ConversationService conversationService, UserService userService) {
        this.conversationService = conversationService;
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('conversation:view')")
    public Result<Conversation> create(@PathVariable Long workspaceId,
                                        @Valid @RequestBody ConversationCreateRequest request,
                                        Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        Conversation conversation = conversationService.create(workspaceId, user.getId(), request.getTitle());
        return Result.success(conversation);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('conversation:view')")
    public Result<List<Conversation>> list(@PathVariable Long workspaceId,
                                            Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        List<Conversation> conversations = conversationService.listByWorkspace(workspaceId, user.getId());
        return Result.success(conversations);
    }

    @GetMapping("/{conversationId}")
    @PreAuthorize("hasAuthority('conversation:view')")
    public Result<Conversation> get(@PathVariable Long workspaceId,
                                     @PathVariable Long conversationId,
                                     Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        Conversation conversation = conversationService.get(conversationId, workspaceId, user.getId());
        return Result.success(conversation);
    }

    @PatchMapping("/{conversationId}")
    @PreAuthorize("hasAuthority('conversation:manage')")
    public Result<Conversation> update(@PathVariable Long workspaceId,
                                        @PathVariable Long conversationId,
                                        @Valid @RequestBody ConversationUpdateRequest request,
                                        Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        Conversation conversation = conversationService.updateTitle(conversationId, workspaceId, user.getId(), request.getTitle());
        return Result.success(conversation);
    }

    @DeleteMapping("/{conversationId}")
    @PreAuthorize("hasAuthority('conversation:manage')")
    public Result<Void> delete(@PathVariable Long workspaceId,
                                @PathVariable Long conversationId,
                                Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        conversationService.delete(conversationId, workspaceId, user.getId());
        return Result.success();
    }

    @GetMapping("/{conversationId}/messages")
    @PreAuthorize("hasAuthority('conversation:view')")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long workspaceId,
                                                   @PathVariable Long conversationId,
                                                   Authentication authentication) {
        User user = userService.findByUsername(authentication.getName());
        List<ChatMessage> messages = conversationService.getMessages(conversationId, workspaceId, user.getId());
        return Result.success(messages);
    }
}