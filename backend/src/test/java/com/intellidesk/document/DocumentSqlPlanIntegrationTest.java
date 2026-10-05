package com.intellidesk.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic DB access audit, not a retrieval benchmark or a production performance result. */
@Testcontainers
class DocumentSqlPlanIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Test void actualMigrationConstraintsAndRepresentativePlans() throws Exception {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).target("6").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        jdbc.update("INSERT INTO sys_user(id,username,password_hash) VALUES(100,'synthetic-plan-user','non-login-fixture')");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT 100,id FROM sys_role WHERE code='MEMBER'");
        jdbc.update("INSERT INTO workspace(id,name,owner_id) VALUES(100,'synthetic-plan-workspace',100)");
        jdbc.update("INSERT INTO knowledge_base(id,workspace_id,name,created_by) SELECT g,100,'synthetic-kb-'||g,100 FROM generate_series(1,200) g");
        jdbc.execute("""
            INSERT INTO document(id,knowledge_base_id,original_file_name,file_extension,content_type,file_size,
                checksum_sha256,bucket_name,object_key,status,chunk_strategy,chunk_size,chunk_overlap,created_by)
            SELECT g,1+(g%200),'synthetic-'||g,'txt','text/plain',100,lpad(g::text,64,'0'),
                'synthetic','synthetic/'||g,'COMPLETED','RECURSIVE',1000,150,100
            FROM generate_series(1,20000) g
            """);
        // Each task has a distinct document and UUID; no real corpus is loaded.
        jdbc.execute("""
            INSERT INTO document_index_task(document_id,status,message_id,requested_by,lease_until)
            SELECT id,CASE WHEN id<=1000 THEN 'PROCESSING' ELSE 'SUCCEEDED' END,gen_random_uuid(),100,
                CASE WHEN id<=50 THEN now()-interval '1 minute' ELSE now()+interval '1 hour' END
            FROM document
            """);
        jdbc.execute("ANALYZE document_index_task"); jdbc.execute("ANALYZE document"); jdbc.execute("ANALYZE knowledge_base");
        jdbc.execute("ANALYZE sys_role"); jdbc.execute("ANALYZE sys_user_role");
        String leaseSql="SELECT * FROM document_index_task WHERE status='PROCESSING' AND lease_until < CURRENT_TIMESTAMP ORDER BY lease_until LIMIT 20";
        var plans=new LinkedHashMap<String,Object>();
        plans.put("dataset",Map.of("synthetic",true,"documents",20000,"tasks",20000,"processingTasks",1000,"expiredLeases",50));
        plans.put("leaseSql",leaseSql); plans.put("leaseBefore",plan(jdbc,leaseSql));
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc.execute("ANALYZE document_index_task"); plans.put("leaseAfter",plan(jdbc,leaseSql));
        plans.put("documentList",plan(jdbc,"SELECT * FROM document WHERE knowledge_base_id=7 ORDER BY created_at DESC LIMIT 20 OFFSET 0"));
        plans.put("knowledgeList",plan(jdbc,"SELECT * FROM knowledge_base WHERE workspace_id=100 ORDER BY created_at DESC LIMIT 20 OFFSET 0"));
        plans.put("roles",plan(jdbc,"SELECT r.code FROM sys_role r JOIN sys_user_role ur ON r.id=ur.role_id WHERE ur.user_id=100"));
        Path out=Path.of("target/java-engineering/sql-plans.json"); Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),plans);
        assertThat(plans.get("leaseAfter").toString()).contains("idx_task_status_lease");
        assertThatThrownBy(()->jdbc.update("INSERT INTO document_index_task(document_id,status,message_id,requested_by) VALUES(1,'PENDING',gen_random_uuid(),100)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_index_task",Integer.class)).isEqualTo(20000);
    }
    private String plan(JdbcTemplate jdbc,String sql) { return jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+sql,String.class); }
}
