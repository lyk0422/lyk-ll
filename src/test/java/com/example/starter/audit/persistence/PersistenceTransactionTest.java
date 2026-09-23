package com.example.starter.audit.persistence;

import com.example.starter.audit.engine.DigestSupport;
import com.example.starter.audit.model.Stage;
import com.example.starter.audit.model.StageState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2(MODE=MySQL) 事务与约束边界测试：验证真实数据库的原子提交、回滚与唯一约束，
 * 不使用 mock 或内存 Map 替代数据访问。
 */
@SpringBootTest
class PersistenceTransactionTest {

    @TempDir
    static Path artifactDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:audit_tx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("audit.artifacts.dir", () -> artifactDir.toString());
    }

    @Autowired
    private TaskAuditRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM task_checkpoint");
        jdbc.update("DELETE FROM task_stage");
        jdbc.update("DELETE FROM task_run");
    }

    @Test
    void stageSuccessAndCheckpointCommitAtomicallyAndRollBackTogether() {
        long taskId = newTask("tx-1");
        long stageId = repository.insertStage(taskId, Stage.PERCEPTION, 1);
        String metrics = new DigestSupport().canonicalJson(java.util.Map.of("k", 1.0d));
        RuntimeException boom = new RuntimeException("模拟提交后段失败");

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            repository.markStageSuccess(stageId, new TaskAuditRepository.StageSuccessData(
                    metrics, "task-x/p.out", "a".repeat(64),
                    "b".repeat(64), "replay cmd"), Instant.now());
            repository.upsertCheckpoint(checkpoint(taskId, "perception", 1,
                    "b".repeat(64), "a".repeat(64), metrics));
            throw boom;
        })).isSameAs(boom);

        // 回滚后：阶段仍为初始 PENDING，检查点不存在
        StageRow stage = repository.findLatestStage(taskId, Stage.PERCEPTION).orElseThrow();
        assertThat(stage.state()).isEqualTo("PENDING");
        assertThat(stage.metricsJson()).isNull();
        assertThat(repository.listCheckpoints(taskId)).isEmpty();
    }

    @Test
    void requestIdUniqueConstraintRejectsDuplicate() {
        newTask("dup-request");
        assertThatThrownBy(() -> repository.insertTask("dup-request", "hash-x",
                snapshot())).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void checkpointUpsertOverwritesPerTaskStage() {
        long taskId = newTask("cp-1");
        String metrics1 = new DigestSupport().canonicalJson(java.util.Map.of("k", 1.0d));
        String metrics2 = new DigestSupport().canonicalJson(java.util.Map.of("k", 2.0d));

        repository.upsertCheckpoint(checkpoint(taskId, "planning", 1,
                "d".repeat(64), "a".repeat(64), metrics1));
        repository.upsertCheckpoint(checkpoint(taskId, "planning", 2,
                "e".repeat(64), "b".repeat(64), metrics2));

        List<CheckpointRow> checkpoints = repository.listCheckpoints(taskId);
        assertThat(checkpoints).hasSize(1);
        assertThat(checkpoints.get(0).attempt()).isEqualTo(2);
        assertThat(checkpoints.get(0).outputDigest()).isEqualTo("e".repeat(64));
        assertThat(checkpoints.get(0).metricsJson()).isEqualTo(metrics2);
    }

    @Test
    void terminalStateUpdatePersistsErrorMessage() {
        long taskId = newTask("term-1");
        long stageId = repository.insertStage(taskId, Stage.CONTROL, 1);
        // H2 TIMESTAMP(6) 精度为微秒
        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        repository.markStageRunning(stageId, at);
        repository.markStageTerminal(stageId, StageState.FAILED, "合成失败原因", at.plusSeconds(1));

        StageRow row = repository.findLatestStage(taskId, Stage.CONTROL).orElseThrow();
        assertThat(row.state()).isEqualTo("FAILED");
        assertThat(row.startedAt()).isEqualTo(at);
        assertThat(row.finishedAt()).isEqualTo(at.plusSeconds(1));
        assertThat(row.errorMessage()).isEqualTo("合成失败原因");
    }

    private long newTask(String requestId) {
        return repository.insertTask(requestId, "hash-" + requestId, snapshot());
    }

    private TaskAuditRepository.TaskBindingsSnapshot snapshot() {
        return new TaskAuditRepository.TaskBindingsSnapshot(
                "code-v1", "config-v1", "map-v1", "bag-v1", 1L, "default", null);
    }

    private CheckpointRow checkpoint(long taskId, String stage, int attempt,
                                     String output, String artifact, String metrics) {
        return new CheckpointRow(null, taskId, stage, attempt,
                "code-v1", "config-v1", "map-v1", "bag-v1", 1L,
                "0".repeat(64), output, artifact, metrics, Instant.now());
    }
}
