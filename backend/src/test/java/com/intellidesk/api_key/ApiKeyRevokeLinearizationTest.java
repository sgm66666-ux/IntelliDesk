package com.intellidesk.api_key;

import com.intellidesk.TestInfrastructureConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@Sql(scripts = "classpath:db/testdata/V5_test_schema.sql")
class ApiKeyRevokeLinearizationTest {

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long workspaceId;
    private Long userId;

    @BeforeEach
    void setUp() {
        apiKeyMapper.delete(new LambdaQueryWrapper<>());
        jdbcTemplate.execute("DELETE FROM api_key");
        jdbcTemplate.execute("DELETE FROM workspace_member");
        jdbcTemplate.execute("DELETE FROM workspace WHERE id >= 9000");
        jdbcTemplate.execute("DELETE FROM sys_user WHERE id >= 9000");

        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, nickname, status) " +
                "VALUES (9001, 'revoke_test_user', '$2a$10$dummy', 'revoke@test.com', 'Revoke Tester', 1)");
        userId = 9001L;

        jdbcTemplate.update("INSERT INTO workspace (id, name, description, owner_id, created_at) " +
                "VALUES (9001, 'Revoke Test WS', 'test', 9001, CURRENT_TIMESTAMP)");
        workspaceId = 9001L;

        jdbcTemplate.update("INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) " +
                "VALUES (9001, 9001, 'OWNER', CURRENT_TIMESTAMP)");
    }

    @Test
    void shouldRejectAuthenticationAfterRevokeCommit() throws Exception {
        String secret = "test-secret-123";
        String keyHash = passwordEncoder.encode(secret);

        ApiKey apiKey = new ApiKey();
        apiKey.setWorkspaceId(workspaceId);
        apiKey.setUserId(userId);
        apiKey.setName("Test Key");
        apiKey.setKeyPrefix("lineariz");
        apiKey.setKeyHash(keyHash);
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");
        apiKey.setCreatedAt(LocalDateTime.now());
        apiKeyMapper.insert(apiKey);

        Long keyId = apiKey.getId();

        // Phase 1: Before revoke, authentication should succeed
        ApiKey found = apiKeyMapper.selectOne(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getKeyPrefix, "lineariz")
                        .eq(ApiKey::getStatus, "ACTIVE"));
        assertThat(found).isNotNull();
        assertThat(passwordEncoder.matches(secret, found.getKeyHash())).isTrue();

        // Phase 2: Revoke the key
        apiKey.setStatus("REVOKED");
        apiKey.setRevokedAt(LocalDateTime.now());
        apiKeyMapper.updateById(apiKey);

        // Phase 3: After revoke, authentication should fail
        ApiKey afterRevoke = apiKeyMapper.selectOne(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getKeyPrefix, "lineariz")
                        .eq(ApiKey::getStatus, "ACTIVE"));
        assertThat(afterRevoke).isNull();
    }

    @Test
    void shouldLinearizeRevokeAtCommit() throws Exception {
        String secret = "test-secret-456";
        String keyHash = passwordEncoder.encode(secret);

        ApiKey apiKey = new ApiKey();
        apiKey.setWorkspaceId(workspaceId);
        apiKey.setUserId(userId);
        apiKey.setName("Concurrency Key");
        apiKey.setKeyPrefix("concurrn");
        apiKey.setKeyHash(keyHash);
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");
        apiKey.setCreatedAt(LocalDateTime.now());
        apiKeyMapper.insert(apiKey);

        Long keyId = apiKey.getId();

        CountDownLatch revokeReady = new CountDownLatch(1);
        CountDownLatch preCommitAuthDone = new CountDownLatch(1);
        AtomicBoolean preCommitAuthSuccess = new AtomicBoolean(false);
        AtomicBoolean postCommitAuthSuccess = new AtomicBoolean(false);

        Thread revoker = new Thread(() -> {
            transactionTemplate.executeWithoutResult(status -> {
                ApiKey toRevoke = apiKeyMapper.selectById(keyId);
                toRevoke.setStatus("REVOKED");
                toRevoke.setRevokedAt(LocalDateTime.now());
                apiKeyMapper.updateById(toRevoke);
                revokeReady.countDown();
                try {
                    preCommitAuthDone.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });

        Thread authenticator = new Thread(() -> {
            try {
                revokeReady.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Pre-commit auth: revoker has set status=REVOKED but not committed
            // In READ COMMITTED, this thread sees the original ACTIVE status
            transactionTemplate.executeWithoutResult(status -> {
                ApiKey found = apiKeyMapper.selectOne(
                        new LambdaQueryWrapper<ApiKey>()
                                .eq(ApiKey::getKeyPrefix, "concurrn")
                                .eq(ApiKey::getStatus, "ACTIVE"));
                preCommitAuthSuccess.set(found != null
                        && passwordEncoder.matches(secret, found.getKeyHash()));
            });

            preCommitAuthDone.countDown();

            try {
                revoker.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Post-commit auth: revoker has committed
            transactionTemplate.executeWithoutResult(status -> {
                ApiKey found = apiKeyMapper.selectOne(
                        new LambdaQueryWrapper<ApiKey>()
                                .eq(ApiKey::getKeyPrefix, "concurrn")
                                .eq(ApiKey::getStatus, "ACTIVE"));
                postCommitAuthSuccess.set(found != null
                        && passwordEncoder.matches(secret, found.getKeyHash()));
            });
        });

        revoker.start();
        authenticator.start();
        revoker.join(15000);
        authenticator.join(15000);

        assertThat(preCommitAuthSuccess.get())
                .as("Pre-commit authentication should succeed (sees ACTIVE before revoke commit)")
                .isTrue();
        assertThat(postCommitAuthSuccess.get())
                .as("Post-commit authentication should fail (sees REVOKED after revoke commit)")
                .isFalse();
    }
}