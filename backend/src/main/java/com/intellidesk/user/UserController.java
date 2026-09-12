package com.intellidesk.user;

import com.intellidesk.common.Result;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public Result<User> getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        User user = userService.findByUsername(username);
        return Result.success(user);
    }

    @PutMapping("/me")
    public Result<User> updateCurrentUser(@RequestBody Map<String, String> body,
                                           Authentication authentication) {
        String username = authentication.getName();
        User user = userService.findByUsername(username);
        if (user == null) {
            return Result.error(com.intellidesk.common.ErrorCode.USER_NOT_FOUND);
        }
        if (body.containsKey("nickname")) {
            user.setNickname(body.get("nickname"));
        }
        if (body.containsKey("email")) {
            user.setEmail(body.get("email"));
        }
        userService.updateById(user);
        return Result.success(user);
    }
}