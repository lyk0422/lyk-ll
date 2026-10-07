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
 * 回收重量登记（投料额度）仓储。
 *
 * <p>并发一致性由原子 UPDATE 守卫保证：消费、调减、失效均带状态/版本/余额条件，
 * 投料扣量与复议改判在同一额度行上串行化，只形成一个一致次序。</p>
 */
@Repository
public class RecycleQuotaRepository {

    private static final RowMapper<RecycleQuota> MAPPER = (rs, n) -> {
        RecycleQuota q = new RecycleQuota();
        q.setId(rs.getLong("id"));
        q.setRollNo(rs.getString("roll_no"));
        q.setInitialKg(rs.getBigDecimal("initial_kg"));
        q.setRegisteredKg(rs.getBigDecimal("registered_kg"));
        q.setConsumedKg(rs.getBigDecimal("consumed_kg"));
        q.setInvalidatedKg(rs.getBigDecimal("invalidated_kg"));
        q.setVersion(rs.getInt("version"));
        q.setStatus(rs.getString("status"));
        q.setInvalidReason(rs.getString("invalid_reason"));
        q.setRequestId(rs.getString("request_id"));
        q.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        q.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        Timestamp invalidated = rs.getTimestamp("invalidated_at");
        q.setInvalidatedAt(invalidated == null ? null : invalidated.toLocalDateTime());
        return q;
    };

    private final JdbcTemplate jdbc;

    public RecycleQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记额度：版本 1 起；唯一索引 (roll_no) 保证每卷至多一条。 */
    public void insert(RecycleQuota quota, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO recycle_quota
                            (roll_no, initial_kg, registered_kg, consumed_kg, invalidated_kg,
                             version, status, invalid_reason, request_id, created_at, updated_at)
                        VALUES (?,?,?,0,0,1,'ACTIVE',NULL,?,?,?)
                        """,
                quota.getRollNo(), quota.getInitialKg(), quota.getRegisteredKg(),
                quota.getRequestId(), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    public Optional<RecycleQuota> findByRollNo(String rollNo) {
        List<RecycleQuota> list = jdbc.query(
                "SELECT * FROM recycle_quota WHERE roll_no = ?", MAPPER, rollNo);
        return list.stream().findFirst();
    }

    /**
     * 调减额度：仅允许减少且不得小于累计消费；携带 expectedVersion 乐观并发控制。
     *
     * @return 更新行数；0 表示版本过期、已失效或调减后低于累计消费
     */
    public int reduceQuota(String rollNo, java.math.BigDecimal newRegisteredKg,
                           int expectedVersion, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE recycle_quota
                        SET registered_kg = ?, version = version + 1, updated_at = ?
                        WHERE roll_no = ? AND status = 'ACTIVE' AND version = ?
                          AND ? < registered_kg AND ? >= consumed_kg
                        """,
                newRegisteredKg, Timestamp.valueOf(now), rollNo, expectedVersion,
                newRegisteredKg, newRegisteredKg);
    }

    /**
     * 投料消费：原子扣减可用额度并递增版本；额度不足或已失效时返回 0，由调用方整单回滚。
     * 与复议改判的失效 UPDATE 作用于同一行，行锁保证只形成一个一致次序。
     */
    public int consume(String rollNo, java.math.BigDecimal weightKg, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE recycle_quota
                        SET consumed_kg = consumed_kg + ?, version = version + 1, updated_at = ?
                        WHERE roll_no = ? AND status = 'ACTIVE'
                          AND consumed_kg + ? <= registered_kg
                        """,
                weightKg, Timestamp.valueOf(now), rollNo, weightKg);
    }

    /**
     * 复议改判正品：未消费额度同步失效，已消费凭据保留。
     *
     * @return 更新行数；0 表示该卷无有效额度（未登记或已失效），均视为无需处理
     */
    public int invalidateOnUpgrade(String rollNo, String reason, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE recycle_quota
                        SET status = 'INVALIDATED', invalidated_kg = registered_kg - consumed_kg,
                            invalid_reason = ?, version = version + 1,
                            invalidated_at = ?, updated_at = ?
                        WHERE roll_no = ? AND status = 'ACTIVE'
                        """,
                reason, Timestamp.valueOf(now), Timestamp.valueOf(now), rollNo);
    }
}
