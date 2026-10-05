package com.example.starter.feed.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出栏申请台账仓储。申请（批准预约/已核销/拒绝）逐条落账；
 * 治疗直报重算后在同一事务内自动撤销与新最早出栏日冲突的已批准预约，撤销记录保留可查。
 */
@Repository
public class SlaughterApplicationRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert applicationInsert;

    public SlaughterApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.applicationInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("slaughter_application")
                .usingColumns("request_id", "batch_id", "apply_at_utc", "approved", "status", "reject_reason")
                .usingGeneratedKeyColumns("id");
    }

    /** slaughter_application 行。 */
    public record SlaughterApplicationRow(
            long id,
            String requestId,
            long batchId,
            Instant applyAtUtc,
            boolean approved,
            String status,
            String rejectReason,
            Instant revokedAtUtc,
            String revokeReason
    ) {
    }

    private static final RowMapper<SlaughterApplicationRow> MAPPER = (rs, n) -> new SlaughterApplicationRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("batch_id"),
            DictRepository.toInstant(rs.getTimestamp("apply_at_utc")),
            rs.getBoolean("approved"),
            rs.getString("status"),
            rs.getString("reject_reason"),
            DictRepository.toInstant(rs.getTimestamp("revoked_at_utc")),
            rs.getString("revoke_reason")
    );

    /**
     * 落账一条出栏申请。
     *
     * @return 新申请主键
     */
    public long insert(String requestId, long batchId, Instant applyAtUtc,
                       boolean approved, String status, String rejectReason) {
        Map<String, Object> params = new HashMap<>();
        params.put("request_id", requestId);
        params.put("batch_id", batchId);
        params.put("apply_at_utc", Timestamp.from(applyAtUtc));
        params.put("approved", approved);
        params.put("status", status);
        params.put("reject_reason", rejectReason);
        return applicationInsert.executeAndReturnKey(params).longValue();
    }

    /**
     * 自动撤销与新最早合法出栏时刻冲突的已批准预约（SCHEDULED 且申请时刻早于新最早出栏时刻）。
     * 与休药期重算在同一事务内完成，撤销记录保留可查。
     *
     * @return 被撤销的申请主键列表（升序）
     */
    public List<Long> revokeConflicting(long batchId, Instant newEarliestSlaughterUtc,
                                        Instant revokedAtUtc, String revokeReason) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM slaughter_application WHERE batch_id = ? AND status = 'SCHEDULED' "
                        + "AND apply_at_utc < ? ORDER BY id",
                (rs, n) -> rs.getLong("id"), batchId, Timestamp.from(newEarliestSlaughterUtc));
        for (Long id : ids) {
            jdbc.update("UPDATE slaughter_application SET status = 'REVOKED', revoked_at_utc = ?, "
                            + "revoke_reason = ? WHERE id = ? AND status = 'SCHEDULED'",
                    Timestamp.from(revokedAtUtc), revokeReason, id);
        }
        return ids;
    }

    /**
     * 查询批次全部出栏申请台账（含撤销记录），按主键升序。
     */
    public List<SlaughterApplicationRow> findByBatch(long batchId) {
        return jdbc.query("SELECT * FROM slaughter_application WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }
}
