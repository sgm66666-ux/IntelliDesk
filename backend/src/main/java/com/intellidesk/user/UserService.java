package com.intellidesk.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService extends ServiceImpl<UserMapper, User> {

    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    public UserService(PasswordEncoder passwordEncoder, UserMapper userMapper) {
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
    }

    @Transactional
    public User register(String username, String password, String email, String nickname) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setNickname(nickname != null ? nickname : username);
        user.setStatus(1);
        save(user);

        Long memberRoleId = userMapper.findRoleIdByCode("MEMBER");
        if (memberRoleId != null) {
            userMapper.insertUserRole(user.getId(), memberRoleId);
        }

        return user;
    }

    public User findByUsername(String username) {
        return lambdaQuery().eq(User::getUsername, username).one();
    }

    public boolean verifyPassword(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    public List<String> getRoleCodes(Long userId) {
        return userMapper.findRoleCodesByUserId(userId);
    }

    public List<String> getPermissionCodes(Long userId) {
        return userMapper.findPermissionCodesByUserId(userId);
    }
}