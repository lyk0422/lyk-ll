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
 * 治疗性用药直报仓储。直报行携带目录快照（休药天数），
 * 同一批次同一给药时刻由唯一约束保证只允许一条进行中直报。
 */
@Repository
public class TreatmentReportRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert reportInsert;

    public TreatmentReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.reportInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("treatment_report")
                .usingColumns("request_id", "batch_id", "batch_no", "drug_code", "drug_name",
                        "withdrawal_days", "administered_at_utc", "confirmed_by1", "confirmed_by2",
                        "late", "fingerprint")
                .usingGeneratedKeyColumns("id");
    }

    /** treatment_report 行（仅含重算所需字段）。 */
    public record TreatmentRow(
            long id,
            long batchId,
            String drugCode,
            int withdrawalDays,
            Instant administeredAtUtc,
            boolean late
    ) {
    }

    private static final RowMapper<TreatmentRow> MAPPER = (rs, n) -> new TreatmentRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("drug_code"),
            rs.getInt("withdrawal_days"),
            DictRepository.toInstant(rs.getTimestamp("administered_at_utc")),
            rs.getBoolean("late")
    );

    /**
     * 插入直报记录；同批次同给药时刻或同 requestId 触发唯一约束（由服务层转 409）。
     *
     * @return 新直报记录 id
     */
    public long insert(String requestId, long batchId, String batchNo, String drugCode, String drugName,
                       int withdrawalDays, Instant administeredAtUtc, String confirmedBy1, String confirmedBy2,
                       boolean late, String fingerprint) {
        Map<String, Object> params = new HashMap<>();
        params.put("request_id", requestId);
        params.put("batch_id", batchId);
        params.put("batch_no", batchNo);
        params.put("drug_code", drugCode);
        params.put("drug_name", drugName);
        params.put("withdrawal_days", withdrawalDays);
        params.put("administered_at_utc", Timestamp.from(administeredAtUtc));
        params.put("confirmed_by1", confirmedBy1);
        params.put("confirmed_by2", confirmedBy2);
        params.put("late", late);
        params.put("fingerprint", fingerprint);
        return reportInsert.executeAndReturnKey(params).longValue();
    }

    /**
     * 取批次全部非迟到直报（用于合并口径重算休药期）。
     */
    public List<TreatmentRow> findEffectiveByBatch(long batchId) {
        return jdbc.query("SELECT * FROM treatment_report WHERE batch_id = ? AND late = FALSE ORDER BY id",
                MAPPER, batchId);
    }
}
