package com.intellidesk.api_key;

import com.intellidesk.TestInfrastructureConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@Testcontainers
class Phase6Wave1MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("intellidesk_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.autoconfigure.exclude", () -> "");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateApiKeyTableWithAllColumns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_name = 'api_key' ORDER BY ordinal_position");

        List<String> columnNames = columns.stream()
                .map(m -> (String) m.get("column_name"))
                .toList();

        assertThat(columnNames).contains(
                "id", "workspace_id", "user_id", "name", "key_prefix", "key_hash",
                "scope", "status", "expires_at", "last_used_at", "created_at", "revoked_at");
    }

    @Test
    void shouldHaveForeignKeyConstraints() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> constraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                "WHERE table_name = 'api_key' AND constraint_type = 'FOREIGN KEY'");

        List<String> names = constraints.stream()
                .map(m -> (String) m.get("constraint_name"))
                .toList();

        assertThat(names).contains("fk_api_key_workspace", "fk_api_key_user");
    }

    @Test
    void shouldHaveUniquePrefixConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> constraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                "WHERE table_name = 'api_key' AND constraint_type = 'UNIQUE'");

        List<String> names = constraints.stream()
                .map(m -> (String) m.get("constraint_name"))
                .toList();

        assertThat(names).contains("uq_api_key_prefix");
    }

    @Test
    void shouldHaveStatusCheckConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> constraints = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                "WHERE table_name = 'api_key' AND constraint_type = 'CHECK'");

        List<String> names = constraints.stream()
                .map(m -> (String) m.get("constraint_name"))
                .toList();

        assertThat(names).contains("chk_api_key_status");
    }

    @Test
    void shouldHaveRequiredIndexes() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'api_key'");

        List<String> names = indexes.stream()
                .map(m -> (String) m.get("indexname"))
                .toList();

        assertThat(names).contains("idx_api_key_prefix", "idx_api_key_workspace", "idx_api_key_user");
    }

    @Test
    void shouldNotStorePlaintextFullKey() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'api_key'", String.class);

        assertThat(columns).doesNotContain("full_key", "plaintext_key", "secret", "raw_key");
    }
}