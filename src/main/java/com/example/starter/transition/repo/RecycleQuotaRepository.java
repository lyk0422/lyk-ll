package com.example.starter.transition.repo;

import com.example.starter.transition.model.RecycleQuota;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 回收额度仓储：每卷至多一次登记（唯一索引保证）；
 * 扣量、调减与失效均为条件更新，并发下由行锁与版本号形成唯一次序。
 */
@Repository
public class RecycleQuotaRepository {

    private static final RowMapper<RecycleQuota> MAPPER = (rs, n) -> {
        RecycleQuota q = new RecycleQuota();
        q.setId(rs.getLong("id"));
        q.setRollNo(rs.getString("roll_no"));
        q.setRegisteredKg(rs.getBigDecimal("registered_kg"));
        q.setCurrentKg(rs.getBigDecimal("current_kg"));
        q.setConsumedKg(rs.getBigDecimal("consumed_kg"));
        q.setVersion(rs.getInt("version"));
        q.setStatus(rs.getString("status"));
        q.setInvalidatedReason(rs.getString("invalidated_reason"));
        q.setRequestId(rs.getString("request_id"));
        q.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        q.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return q;
    };

    private final JdbcTemplate jdbc;

    public RecycleQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记回收额度：版本从 1 起，状态 ACTIVE。 */
    public void insert(RecycleQuota quota, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO recycle_quota
                            (roll_no, registered_kg, current_kg, consumed_kg, version,
                             status, invalidated_reason, request_id, created_at, updated_at)
                        VALUES (?, ?, ?, 0, 1, 'ACTIVE', NULL, ?, ?, ?)
                        """,
                quota.getRollNo(), quota.getRegisteredKg(), quota.getRegisteredKg(),
                quota.getRequestId(), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public Optional<RecycleQuota> findByRollNo(String rollNo) {
        List<RecycleQuota> list = jdbc.query(
                "SELECT * FROM recycle_quota WHERE roll_no = ?", MAPPER, rollNo);
        return list.stream().findFirst();
    }

    /**
     * 调减额度（乐观版本控制）：仅当版本匹配且额度仍有效时生效。
     *
     * @return 影响行数；0 表示版本过期或额度已失效
     */
    public int reduceWithVersion(String rollNo, int expectedVersion, java.math.BigDecimal newQuotaKg,
                                 LocalDateTime now) {
        return jdbc.update("""
                        UPDATE recycle_quota
                        SET current_kg = ?, version = version + 1, updated_at = ?
                        WHERE roll_no = ? AND version = ? AND status = 'ACTIVE'
                        """,
                newQuotaKg, Timestamp.valueOf(now), rollNo, expectedVersion);
    }

    /**
     * 投料扣量：仅当额度有效且未消费余额充足时生效，与版本自增同事务。
     *
     * @return 影响行数；0 表示额度不存在、已失效或余额不足
     */
    public int consume(String rollNo, java.math.BigDecimal kg, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE recycle_quota
                        SET consumed_kg = consumed_kg + ?, version = version + 1, updated_at = ?
                        WHERE roll_no = ? AND status = 'ACTIVE' AND current_kg - consumed_kg >= ?
                        """,
                kg, Timestamp.valueOf(now), rollNo, kg);
    }

    /**
     * 复议改判正品时同步失效：未消费额度清零（当前额度降为累计消费），已消费凭据保留。
     *
     * @return 影响行数；0 表示无有效额度（未登记或已失效）
     */
    public int invalidateOnUpgrade(String rollNo, String reason, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE recycle_quota
                        SET status = 'INVALIDATED', invalidated_reason = ?,
                            current_kg = consumed_kg, version = version + 1, updated_at = ?
                        WHERE roll_no = ? AND status = 'ACTIVE'
                        """,
                reason, Timestamp.valueOf(now), rollNo);
    }
}
