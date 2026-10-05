package com.intellidesk.document;

import com.intellidesk.TestInfrastructureConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@Testcontainers
class DocumentTaskConcurrencyIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.enabled",()->true);
        registry.add("spring.sql.init.mode",()->"never");
    }
    @Autowired DocumentProcessingService processing;
    @Autowired DocumentIndexTaskMapper tasks;
    @Autowired DocumentMapper documents;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void seed() {
        jdbc.execute("DELETE FROM document_index_task"); jdbc.execute("DELETE FROM document");
        jdbc.execute("DELETE FROM knowledge_base"); jdbc.execute("DELETE FROM workspace"); jdbc.execute("DELETE FROM sys_user");
        jdbc.update("INSERT INTO sys_user(id,username,password_hash) VALUES(100,'synthetic-cas-user','non-login-fixture')");
        jdbc.update("INSERT INTO workspace(id,name,owner_id) VALUES(100,'synthetic-cas-workspace',100)");
        jdbc.update("INSERT INTO knowledge_base(id,workspace_id,name,created_by) VALUES(100,100,'synthetic-cas-kb',100)");
        jdbc.execute("""
            INSERT INTO document(id,knowledge_base_id,original_file_name,file_extension,content_type,file_size,
                checksum_sha256,bucket_name,object_key,status,chunk_strategy,chunk_size,chunk_overlap,created_by)
            VALUES(100,100,'synthetic.txt','txt','text/plain',100,repeat('0',64),'synthetic','synthetic/cas',
                'PENDING','RECURSIVE',1000,150,100)
            """);
        jdbc.update("INSERT INTO document_index_task(id,document_id,status,message_id,requested_by) VALUES(100,100,'QUEUED',gen_random_uuid(),100)");
    }
    @Test void twoConcurrentRealMapperClaimsHaveExactlyOneWinner() throws Exception {
        var taskA=tasks.selectById(100L); var taskB=tasks.selectById(100L);
        var docA=documents.selectById(100L); var docB=documents.selectById(100L);
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var a=workers.submit(()->{ready.countDown(); start.await(); return processing.claimTask(taskA,docA);});
            var b=workers.submit(()->{ready.countDown(); start.await(); return processing.claimTask(taskB,docB);});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var resultA=a.get(10,TimeUnit.SECONDS); var resultB=b.get(10,TimeUnit.SECONDS);
            assertThat((resultA==null ? 0:1)+(resultB==null ? 0:1)).isEqualTo(1);
            var stored=tasks.selectById(100L);
            assertThat(stored.getStatus()).isEqualTo("PROCESSING"); assertThat(stored.getAttemptCount()).isEqualTo(1);
            assertThat(stored.getVersion()).isEqualTo(1); assertThat(documents.selectById(100L).getVersion()).isEqualTo(1);
        } finally { start.countDown(); workers.shutdownNow(); }
    }
    @Test void staleSnapshotCannotReusePreviousAttemptFenceAfterRecovery() {
        var stale=tasks.selectById(100L);
        jdbc.update("UPDATE document_index_task SET status='RETRY_WAIT',attempt_count=1,version=2 WHERE id=100");
        jdbc.update("UPDATE document SET version=2 WHERE id=100");
        assertThat(processing.claimTask(stale,documents.selectById(100L))).isNull();
        assertThat(tasks.selectById(100L).getAttemptCount()).isEqualTo(1);
        assertThat(documents.selectById(100L).getStatus()).isEqualTo("PENDING");
    }
    @Test void exhaustedTaskCannotBeClaimedEvenByFreshPayload() {
        jdbc.update("UPDATE document_index_task SET attempt_count=3 WHERE id=100");
        assertThat(processing.claimTask(tasks.selectById(100L),documents.selectById(100L))).isNull();
        assertThat(tasks.selectById(100L).getStatus()).isEqualTo("QUEUED");
    }
}
