package com.example.starter.fermentation.repo;

import com.example.starter.fermentation.BatchStatus;
import com.example.starter.fermentation.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 发酵罐与批次仓储：罐编码去重、批次创建（数据库唯一键保证一罐一批）与状态流转持久化。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<BatchRow> ROW_MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getLong("tank_id"),
            rs.getString("tank_code"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("filled_at").toInstant(),
            toInstant(rs.getTimestamp("primary_started_at")),
            toInstant(rs.getTimestamp("cooling_at")),
            toInstant(rs.getTimestamp("maturing_at")),
            toInstant(rs.getTimestamp("judged_at")),
            rs.getBoolean("extension_used"),
            (Integer) rs.getObject("active_curve_version"),
            toInstant(rs.getTimestamp("maturity_hold_from")),
            (Boolean) rs.getObject("maturity_passed"),
            toInstant(rs.getTimestamp("transferred_at")));

    private final JdbcClient jdbc;

    public BatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按批次号查询批次（含罐编码），不存在返回 empty。
     */
    public Optional<BatchRow> findByBatchNo(String batchNo) {
        return jdbc.sql("""
                        SELECT b.*, t.tank_code
                        FROM fermentation_batch b
                        JOIN fermentation_tank t ON t.id = b.tank_id
                        WHERE b.batch_no = ?
                        """)
                .param(batchNo)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * 按批次号查询批次，不存在抛 404。
     */
    public BatchRow requireByBatchNo(String batchNo) {
        return findByBatchNo(batchNo)
                .orElseThrow(() -> new DomainException(404, "batch not found: " + batchNo));
    }

    /**
     * 确保发酵罐存在并返回其 id；并发创建时罐编码唯一键兜底。
     */
    public long findOrCreateTank(String tankCode) {
        var existing = jdbc.sql("SELECT id FROM fermentation_tank WHERE tank_code = ?")
                .param(tankCode)
                .query(Long.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO fermentation_tank (tank_code) VALUES (?)")
                    .param(tankCode)
                    .update(keyHolder, "id");
            return keyHolder.getKey().longValue();
        } catch (DuplicateKeyException e) {
            return jdbc.sql("SELECT id FROM fermentation_tank WHERE tank_code = ?")
                    .param(tankCode)
                    .query(Long.class)
                    .single();
        }
    }

    /**
     * 创建批次。batch_no 重复 409；同一罐已有未转出批次时 active_tank_key 唯一键冲突 409，
     * 并发入罐由数据库唯一索引保证只有一个受理。
     */
    public long createBatch(String batchNo, long tankId, Instant filledAt) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("""
                            INSERT INTO fermentation_batch
                                (batch_no, tank_id, status, filled_at, active_curve_version)
                            VALUES (?, ?, 'FILLED', ?, 1)
                            """)
                    .param(batchNo)
                    .param(tankId)
                    .param(Timestamp.from(filledAt))
                    .update(keyHolder, "id");
            return keyHolder.getKey().longValue();
        } catch (DuplicateKeyException e) {
            throw new DomainException(409, duplicateMessage(e, batchNo, tankId));
        }
    }

    /**
     * 首条温度读数触发满罐→主酵，记录主酵开始时刻；仅 FILLED 状态可推进。
     */
    public boolean startPrimaryIfFilled(long batchId, Instant primaryStartedAt) {
        return jdbc.sql("""
                        UPDATE fermentation_batch
                        SET status = 'PRIMARY', primary_started_at = ?
                        WHERE id = ? AND status = 'FILLED'
                        """)
                .param(Timestamp.from(primaryStartedAt))
                .param(batchId)
                .update() > 0;
    }

    /**
     * 降温指令生效：主酵→降温，记录指令时刻与新曲线版本。
     * 与降温指令、曲线版本插入处于同一事务，调用方必须在事务内调用。
     */
    public void markCooling(long batchId, Instant coolingAt, int newVersionNo) {
        int rows = jdbc.sql("""
                        UPDATE fermentation_batch
                        SET status = 'COOLING', cooling_at = ?, active_curve_version = ?
                        WHERE id = ? AND status = 'PRIMARY'
                        """)
                .param(Timestamp.from(coolingAt))
                .param(newVersionNo)
                .param(batchId)
                .update();
        if (rows == 0) {
            throw new DomainException(409, "batch status conflict, cooling requires PRIMARY");
        }
    }

    /**
     * 降温完成进入后熟：降温→后熟，后熟保温计时从该时刻起算（恰 7 天口径）。
     */
    public void markMaturing(long batchId, Instant maturingAt) {
        int rows = jdbc.sql("""
                        UPDATE fermentation_batch
                        SET status = 'MATURING', maturing_at = ?, maturity_hold_from = ?
                        WHERE id = ? AND status = 'COOLING'
                        """)
                .param(Timestamp.from(maturingAt))
                .param(Timestamp.from(maturingAt))
                .param(batchId)
                .update();
        if (rows == 0) {
            throw new DomainException(409, "batch status conflict, enter maturation requires COOLING");
        }
    }

    /**
     * 成熟度综合判定落库：记录判定时刻、合格标记与判定后状态。
     * 合格保持 MATURING 等待转出；首次不合格由调用方先延长（仍回 MATURING）；
     * 延长后仍不合格置 REVIEW（整批复审）。
     */
    public void applyJudgment(long batchId, Instant judgedAt, boolean passed, BatchStatus statusAfter) {
        int rows = jdbc.sql("""
                        UPDATE fermentation_batch
                        SET judged_at = ?, maturity_passed = ?, status = ?
                        WHERE id = ?
                        """)
                .param(Timestamp.from(judgedAt))
                .param(passed)
                .param(statusAfter.name())
                .param(batchId)
                .update();
        if (rows == 0) {
            throw new DomainException(404, "batch not found: " + batchId);
        }
    }

    /**
     * 首次判定不合格时延长后熟一次：保温计时起点顺延为判定时刻，状态仍回到 MATURING。
     */
    public void grantMaturityExtension(long batchId, Instant judgedAt) {
        int rows = jdbc.sql("""
                        UPDATE fermentation_batch
                        SET extension_used = TRUE, maturity_hold_from = ?, status = 'MATURING'
                        WHERE id = ? AND status = 'MATURING' AND extension_used = FALSE
                        """)
                .param(Timestamp.from(judgedAt))
                .param(batchId)
                .update();
        if (rows == 0) {
            throw new DomainException(409, "maturity extension already used or status conflict");
        }
    }

    /**
     * 清酒转出：后熟判定合格的批次进入 TRANSFERRED 终态，active_tank_key 计算列随之释放罐占用。
     */
    public void markTransferred(long batchId, Instant transferredAt) {
        int rows = jdbc.sql("""
                        UPDATE fermentation_batch
                        SET status = 'TRANSFERRED', transferred_at = ?
                        WHERE id = ? AND status = 'MATURING' AND maturity_passed = TRUE
                        """)
                .param(Timestamp.from(transferredAt))
                .param(batchId)
                .update();
        if (rows == 0) {
            throw new DomainException(409, "transfer requires maturity passed in MATURING stage");
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String duplicateMessage(DuplicateKeyException e, String batchNo, long tankId) {
        String message = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : "";
        if (message != null && message.toLowerCase().contains("uk_active_tank")) {
            return "tank already has an in-progress batch, tankId=" + tankId;
        }
        return "batch already exists: " + batchNo;
    }
}
