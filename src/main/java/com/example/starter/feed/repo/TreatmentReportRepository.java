package com.example.starter.feed.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 治疗性用药直报仓储。直报落库后由服务层在同一事务内触发批次休药期重算。
 */
@Repository
public class TreatmentReportRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert reportInsert;

    public TreatmentReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.reportInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("treatment_report")
                .usingColumns("request_id", "batch_id", "administered_at_utc", "max_withdrawal_days",
                        "confirmer_a", "confirmer_b", "late", "fingerprint")
                .usingGeneratedKeyColumns("id");
    }

    /** treatment_report 行（仅重算所需字段）。 */
    public record TreatmentReportRow(
            long id,
            long batchId,
            Instant administeredAtUtc,
            int maxWithdrawalDays,
            boolean late
    ) {
    }

    private static final RowMapper<TreatmentReportRow> MAPPER = (rs, n) -> new TreatmentReportRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            DictRepository.toInstant(rs.getTimestamp("administered_at_utc")),
            rs.getInt("max_withdrawal_days"),
            rs.getBoolean("late")
    );

    /**
     * 插入直报主记录；同一批次同一给药时刻重复直报触发唯一约束。
     *
     * @return 新直报主键；同批次同时刻已存在直报时返回空
     */
    public Optional<Long> insertReport(String requestId, long batchId, Instant administeredAtUtc,
                                       int maxWithdrawalDays, String confirmerA, String confirmerB,
                                       boolean late, String fingerprint) {
        Map<String, Object> params = new HashMap<>();
        params.put("request_id", requestId);
        params.put("batch_id", batchId);
        params.put("administered_at_utc", Timestamp.from(administeredAtUtc));
        params.put("max_withdrawal_days", maxWithdrawalDays);
        params.put("confirmer_a", confirmerA);
        params.put("confirmer_b", confirmerB);
        params.put("late", late);
        params.put("fingerprint", fingerprint);
        try {
            return Optional.of(reportInsert.executeAndReturnKey(params).longValue());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void insertDrug(long reportId, String drugCode, String drugName, int withdrawalDays) {
        jdbc.update("INSERT INTO treatment_report_drug (report_id, drug_code, drug_name, withdrawal_days) "
                        + "VALUES (?,?,?,?)",
                reportId, drugCode, drugName, withdrawalDays);
    }

    /**
     * 取批次全部直报（重算时仅统计非迟到记录；迟到记录不影响已冻结义务）。
     */
    public List<TreatmentReportRow> findByBatch(long batchId) {
        return jdbc.query("SELECT * FROM treatment_report WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }
}
