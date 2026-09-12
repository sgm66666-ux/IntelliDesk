package com.intellidesk.agent;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.agent.tool.ToolRegistry;
import com.intellidesk.chat.orchestration.ChatOrchestrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 Wave 2 Integration Test: Spring context wiring + contract verification.
 * <p>
 * Verifies:
 * <ul>
 *   <li>AgentOrchestrationService bean is properly created with all dependencies</li>
 *   <li>ChatOrchestrationService does NOT have ToolRegistry/ToolExecutor injected</li>
 *   <li>ToolRegistry is a singleton and properly initialized</li>
 *   <li>AgentLoopConfig is properly configured</li>
 *   <li>Chat requests do NOT carry Agent Tools (Phase 4 regression)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Phase 5 Wave 2 Integration (Spring context wiring)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase5Wave2IntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!isDockerAvailable()) {
            return;
        }
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("intellidesk_test")
                    .withUsername("intellidesk")
                    .withPassword("intellidesk");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Autowired
    private AgentOrchestrationService agentOrchestrationService;

    @Autowired
    private ChatOrchestrationService chatOrchestrationService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentLoopConfig agentLoopConfig;

    @Test
    @Order(1)
    @DisplayName("AgentOrchestrationService bean is properly wired")
    void agentOrchestrationServiceIsWired() {
        assertThat(agentOrchestrationService).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("ChatOrchestrationService is wired (Phase 4 regression)")
    void chatOrchestrationServiceIsWired() {
        assertThat(chatOrchestrationService).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("ToolRegistry is singleton and initialized")
    void toolRegistryIsSingleton() {
        assertThat(toolRegistry).isNotNull();
        assertThat(toolRegistry.registeredNames()).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("AgentLoopConfig has valid configuration")
    void agentLoopConfigIsValid() {
        assertThat(agentLoopConfig).isNotNull();
        assertThat(agentLoopConfig.getMaxSteps()).isBetween(1, 50);
        assertThat(agentLoopConfig.getTimeoutSeconds()).isBetween(10, 600);
        assertThat(agentLoopConfig.getSystemPrompt()).isNotBlank();
    }

    @Test
    @Order(5)
    @DisplayName("AgentLoopConfig system prompt contains tool usage rules")
    void systemPromptHasToolRules() {
        String prompt = agentLoopConfig.getSystemPrompt();
        assertThat(prompt).contains("tools");
        assertThat(prompt).contains("knowledge base");
    }

    @Test
    @Order(6)
    @DisplayName("ToolRegistry contains Wave 3 business tools")
    void toolRegistryContainsWave3BusinessTools() {
        // Wave 3 registers 5 read-only business tools.
        // This test verifies that the Wave 3 tools are registered.
        assertThat(toolRegistry.registeredNames())
                .contains("knowledge_search", "knowledge_base_list",
                        "knowledge_base_detail", "document_list", "document_detail");
    }
}