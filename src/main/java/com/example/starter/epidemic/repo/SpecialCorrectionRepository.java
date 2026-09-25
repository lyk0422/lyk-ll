package com.example.starter.epidemic.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 归档后特殊订正仓储。确认过程通过行锁 + 条件更新保证恰好两名不同审核员生效。
 */
@Repository
public class SpecialCorrectionRepository {

    private final JdbcTemplate jdbc;

    public SpecialCorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "id, request_id, card_number, fingerprint, disease_code, diagnosis_type, onset_date, "
                    + "diagnosis_time, timely, late_reason, notes, status, confirmer_1, confirmer_2, "
                    + "applied_version_no, created_at, applied_at";

    private static final RowMapper<SpecialCorrectionPo> MAPPER = (rs, n) -> {
        SpecialCorrectionPo po = new SpecialCorrectionPo();
        po.id = rs.getLong("id");
        po.requestId = rs.getString("request_id");
        po.cardNumber = rs.getString("card_number");
        po.fingerprint = rs.getString("fingerprint");
        po.diseaseCode = rs.getString("disease_code");
        po.diagnosisType = rs.getString("diagnosis_type");
        po.onsetDate = rs.getObject("onset_date", java.time.LocalDate.class);
        po.diagnosisTime = rs.getObject("diagnosis_time", java.time.LocalDateTime.class);
        po.timely = rs.getInt("timely") == 1;
        po.lateReason = rs.getString("late_reason");
        po.notes = rs.getString("notes");
        po.status = rs.getString("status");
        po.confirmer1 = rs.getString("confirmer_1");
        po.confirmer2 = rs.getString("confirmer_2");
        Integer v = rs.getObject("applied_version_no", Integer.class);
        po.appliedVersionNo = rs.wasNull() ? null : v;
        po.createdAt = rs.getObject("created_at", LocalDateTime.class);
        po.appliedAt = rs.getObject("applied_at", LocalDateTime.class);
        return po;
    };

    public void insert(SpecialCorrectionPo po) {
        jdbc.update("INSERT INTO special_correction "
                        + "(request_id, card_number, fingerprint, disease_code, diagnosis_type, onset_date, "
                        + "diagnosis_time, timely, late_reason, notes, status, confirmer_1, confirmer_2, "
                        + "applied_version_no, created_at, applied_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.requestId, po.cardNumber, po.fingerprint, po.diseaseCode, po.diagnosisType, po.onsetDate,
                po.diagnosisTime, po.timely ? 1 : 0, po.lateReason, po.notes, po.status,
                po.confirmer1, po.confirmer2, po.appliedVersionNo, po.createdAt, po.appliedAt);
    }

    public Optional<SpecialCorrectionPo> findByRequestId(String requestId) {
        List<SpecialCorrectionPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM special_correction WHERE request_id = ?",
                MAPPER, requestId);
        return list.stream().findFirst();
    }

    /**
     * 加行锁读取（须在事务内），用于串行化同一特殊订正的确认动作。
     */
    public SpecialCorrectionPo findForUpdate(String requestId) {
        List<SpecialCorrectionPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM special_correction WHERE request_id = ? FOR UPDATE",
                MAPPER, requestId);
        return list.stream().findFirst().orElse(null);
    }

    /**
     * 写入第一确认人（仅当尚无人确认）。
     */
    public int setConfirmer1(String requestId, String reviewer) {
        return jdbc.update(
                "UPDATE special_correction SET confirmer_1 = ? "
                        + "WHERE request_id = ? AND status = 'PENDING' AND confirmer_1 IS NULL",
                reviewer, requestId);
    }

    /**
     * 写入第二确认人（仅当第一确认人已存在且与当前审核员不同）。
     */
    public int setConfirmer2(String requestId, String reviewer) {
        return jdbc.update(
                "UPDATE special_correction SET confirmer_2 = ? "
                        + "WHERE request_id = ? AND status = 'PENDING' "
                        + "AND confirmer_1 IS NOT NULL AND confirmer_2 IS NULL AND confirmer_1 <> ?",
                reviewer, requestId, reviewer);
    }

    /**
     * 两名不同审核员齐备后标记生效并记录新版本号。
     */
    public int markApplied(String requestId, int versionNo, LocalDateTime now) {
        return jdbc.update(
                "UPDATE special_correction SET status = 'APPLIED', applied_version_no = ?, applied_at = ? "
                        + "WHERE request_id = ? AND status = 'PENDING' "
                        + "AND confirmer_1 IS NOT NULL AND confirmer_2 IS NOT NULL",
                versionNo, now, requestId);
    }
}
