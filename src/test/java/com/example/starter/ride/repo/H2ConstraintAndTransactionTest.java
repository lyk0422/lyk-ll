package com.example.starter.ride.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.ride.AbstractRideTest;

/**
 * 直接验证 H2（MODE=MySQL）的唯一约束与事务回滚边界，不使用 mock 或内存 Map 代替。
 */
class H2ConstraintAndTransactionTest extends AbstractRideTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 26);
    private static final Instant NOW = Instant.parse("2026-09-26T02:00:00Z");

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OpenEventRepository openEventRepository;

    @Autowired
    private TrialRunRepository trialRunRepository;

    @Autowired
    private StatusEventRepository statusEventRepository;

    @BeforeEach
    void setUp() {
        resetAndSeed();
    }

    @Test
    @DisplayName("open_event (设备, 营业日, 开放序号) 唯一：并发重复序号只能插入一条")
    void openEventUniqueConstraintEnforced() {
        assertTrue(openEventRepository.insertIfAbsent("req-1", "R001", BIZ_DATE, 1, NOW));
        // 同序号再次插入被唯一约束拒绝。
        org.springframework.dao.DuplicateKeyException direct = assertThrows(
                DuplicateKeyException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO open_event (request_id, ride_id, biz_date, open_seq, created_at) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        "req-2", "R001", BIZ_DATE, 1, Timestamp.from(NOW)));
        assertTrue(direct.getMessage() != null);
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM open_event WHERE ride_id = 'R001'", Long.class));

        // request_id 也唯一。
        assertThrows(DuplicateKeyException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO open_event (request_id, ride_id, biz_date, open_seq, created_at) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        "req-1", "R001", BIZ_DATE, 2, Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("trial_run (设备, 营业日, 次序) 与 request_id 唯一约束生效")
    void trialRunUniqueConstraintsEnforced() {
        trialRunRepository.insert("tr-1", "R001", BIZ_DATE, 1, true, true, true, true, true, NOW);
        assertThrows(DuplicateKeyException.class,
                () -> trialRunRepository.insert("tr-2", "R001", BIZ_DATE, 1,
                        true, true, true, true, true, NOW));
        assertThrows(DuplicateKeyException.class,
                () -> trialRunRepository.insert("tr-1", "R001", BIZ_DATE, 2,
                        true, true, true, true, true, NOW));
        assertEquals(1L, trialRunRepository.countTotalAttempts("R001", BIZ_DATE));
    }

    @Test
    @DisplayName("事务内先写事件后抛异常：全部写入随事务回滚，最终数据无残留")
    void transactionRollsBackAllWrites() {
        long before = statusEventRepository.countByType("R001", BIZ_DATE, "OPEN");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            statusEventRepository.append("R001", BIZ_DATE, "OPEN", "req-tx", "将回滚的事件", NOW);
            jdbcTemplate.update(
                    "INSERT INTO ride_status_event (ride_id, biz_date, event_type, request_id, detail, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    "R001", BIZ_DATE, "REOPEN", "req-tx-2", "同样回滚", Timestamp.from(NOW));
            throw new IllegalStateException("模拟业务失败，触发回滚");
        }));
        long after = statusEventRepository.countByType("R001", BIZ_DATE, "OPEN");
        assertEquals(before, after, "失败事务中的事件必须全部回滚");
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ride_status_event WHERE request_id IN ('req-tx','req-tx-2')",
                Long.class));
    }

    @Test
    @DisplayName("外层事务提交后嵌套计数可见，确认 H2 事务隔离与连接池工作正常")
    void committedWritesAreVisible() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                statusEventRepository.append("R001", BIZ_DATE, "ARCHIVE", "req-commit", "提交事件", NOW));
        assertEquals(1L, statusEventRepository.countByType("R001", BIZ_DATE, "ARCHIVE"));
    }
}
