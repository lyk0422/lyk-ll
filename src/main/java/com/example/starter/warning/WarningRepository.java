package com.example.starter.warning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 大风预警与路段冻结数据访问。
 */
@Repository
public class WarningRepository {

    private final JdbcClient jdbc;

    public WarningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 预警行。status：ACTIVE 生效中 / LIFTED 已解除。 */
    public record WarningRow(long id, String warningNo, String level, String status,
            LocalDateTime publishedAt, LocalDateTime liftedAt) {
    }

    /** 冻结行。status：FROZEN 冻结中 / RELEASED 已解除。 */
    public record FreezeRow(long segmentId, long warningId, LocalDateTime frozenAt, String status) {
    }

    public long insertWarning(String warningNo, String level, LocalDateTime publishedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO gale_warning(warning_no, warning_level, status, published_at)"
                        + " VALUES (?, ?, 'ACTIVE', ?)")
                .params(warningNo, level, publishedAt)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public boolean existsWarningByNo(String warningNo) {
        return jdbc.sql("SELECT COUNT(*) FROM gale_warning WHERE warning_no = ?")
                .param(warningNo)
                .query(Long.class)
                .single() > 0;
    }

    public Optional<WarningRow> findWarningByNo(String warningNo) {
        return jdbc.sql("SELECT * FROM gale_warning WHERE warning_no = ?")
                .param(warningNo)
                .query(this::mapWarning)
                .optional();
    }

    /** 行级锁读取，用于解除预警的串行化。 */
    public Optional<WarningRow> findWarningByNoForUpdate(String warningNo) {
        return jdbc.sql("SELECT * FROM gale_warning WHERE warning_no = ? FOR UPDATE")
                .param(warningNo)
                .query(this::mapWarning)
                .optional();
    }

    public void markLifted(long warningId, LocalDateTime liftedAt) {
        jdbc.sql("UPDATE gale_warning SET status = 'LIFTED', lifted_at = ? WHERE id = ?")
                .params(liftedAt, warningId)
                .update();
    }

    public void insertFreeze(long segmentId, long warningId, LocalDateTime frozenAt) {
        jdbc.sql("INSERT INTO segment_freeze(segment_id, warning_id, frozen_at, status) VALUES (?, ?, ?, 'FROZEN')")
                .params(segmentId, warningId, frozenAt)
                .update();
    }

    public Optional<FreezeRow> findFreeze(long segmentId) {
        return jdbc.sql("SELECT * FROM segment_freeze WHERE segment_id = ?")
                .param(segmentId)
                .query((rs, i) -> new FreezeRow(rs.getLong("segment_id"), rs.getLong("warning_id"),
                        rs.getObject("frozen_at", LocalDateTime.class), rs.getString("status")))
                .optional();
    }

    /** 路段当前是否处于冻结中。 */
    public boolean isFrozen(long segmentId) {
        return jdbc.sql("SELECT COUNT(*) FROM segment_freeze WHERE segment_id = ? AND status = 'FROZEN'")
                .param(segmentId)
                .query(Long.class)
                .single() > 0;
    }

    /** 解除指定预警冻结的全部路段，返回影响行数。 */
    public int releaseFreezes(long warningId) {
        return jdbc.sql("UPDATE segment_freeze SET status = 'RELEASED' WHERE warning_id = ? AND status = 'FROZEN'")
                .param(warningId)
                .update();
    }

    public List<Long> frozenSegmentIds(long warningId) {
        return jdbc.sql("SELECT segment_id FROM segment_freeze WHERE warning_id = ? ORDER BY segment_id")
                .param(warningId)
                .query(Long.class)
                .list();
    }

    private WarningRow mapWarning(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WarningRow(rs.getLong("id"), rs.getString("warning_no"), rs.getString("warning_level"),
                rs.getString("status"), rs.getObject("published_at", LocalDateTime.class),
                rs.getObject("lifted_at", LocalDateTime.class));
    }
}
