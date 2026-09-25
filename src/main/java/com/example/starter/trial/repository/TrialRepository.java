package com.example.starter.trial.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 随机入组与揭盲领域的全部数据访问；SQL 均参数化，时间以 UTC 写入/读取。
 */
@Component
public class TrialRepository {

    private final JdbcTemplate jdbc;

    public TrialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    // ---------------- 中心随机种子 ----------------

    /**
     * 读取“试验+中心”种子；不存在则以给定新种子插入（并发下唯一键冲突时重读）。
     */
    public long getOrCreateSiteSeed(String trialId, String siteCode, long newSeed, Instant now) {
        try {
            return jdbc.queryForObject(
                    "SELECT seed_value FROM site_random_seed WHERE trial_id = ? AND site_code = ?",
                    Long.class, trialId, siteCode);
        } catch (EmptyResultDataAccessException absent) {
            try {
                jdbc.update("INSERT INTO site_random_seed (trial_id, site_code, seed_value, created_at_utc) "
                        + "VALUES (?, ?, ?, ?)", trialId, siteCode, newSeed, ts(now));
                return newSeed;
            } catch (DuplicateKeyException raced) {
                return jdbc.queryForObject(
                        "SELECT seed_value FROM site_random_seed WHERE trial_id = ? AND site_code = ?",
                        Long.class, trialId, siteCode);
            }
        }
    }

    // ---------------- 随机号发号（独立事务，见 RandomNumberSequencer） ----------------

    public int initSequenceIfAbsent(String trialId, String siteCode, Instant now) {
        try {
            return jdbc.update("INSERT INTO site_random_sequence "
                    + "(trial_id, site_code, current_no, created_at_utc, updated_at_utc) "
                    + "VALUES (?, ?, 0, ?, ?)", trialId, siteCode, ts(now), ts(now));
        } catch (DuplicateKeyException alreadyThere) {
            return 0;
        }
    }

    /**
     * 原子递增并返回新随机号。行锁串行化同中心并发发号；调用方在独立事务中提交，
     * 因此外层业务回滚也不会回收该号。
     */
    public long incrementAndGet(String trialId, String siteCode, Instant now) {
        int updated = jdbc.update("UPDATE site_random_sequence SET current_no = current_no + 1, updated_at_utc = ? "
                + "WHERE trial_id = ? AND site_code = ?", ts(now), trialId, siteCode);
        if (updated == 0) {
            throw new IllegalStateException("sequence row missing after init: " + trialId + "/" + siteCode);
        }
        return jdbc.queryForObject(
                "SELECT current_no FROM site_random_sequence WHERE trial_id = ? AND site_code = ?",
                Long.class, trialId, siteCode);
    }

    public long currentSequenceNo(String trialId, String siteCode) {
        try {
            return jdbc.queryForObject(
                    "SELECT current_no FROM site_random_sequence WHERE trial_id = ? AND site_code = ?",
                    Long.class, trialId, siteCode);
        } catch (EmptyResultDataAccessException absent) {
            return 0L;
        }
    }

    /**
     * 在业务事务内获取“试验+中心”的入组串行锁（独立锁表行，SELECT ... FOR UPDATE）。
     * 先确保锁行存在，再加行锁；同中心并发入组在区组分配前串行，不同中心互不阻塞。
     * 锁表与随机号序列表分离，故持锁期间发号器可在独立事务中更新序列行而不死锁。
     */
    public void lockSiteEnrollment(String trialId, String siteCode, Instant now) {
        try {
            jdbc.update("INSERT INTO site_enrollment_lock (trial_id, site_code, created_at_utc) "
                    + "VALUES (?, ?, ?)", trialId, siteCode, ts(now));
        } catch (DuplicateKeyException alreadyThere) {
            // 锁行已存在，直接进入加锁步骤。
        }
        jdbc.queryForObject(
                "SELECT id FROM site_enrollment_lock WHERE trial_id = ? AND site_code = ? FOR UPDATE",
                Long.class, trialId, siteCode);
    }

    /**
     * 中心内已成功入组（已提交可见）的受试者数，用作下一例的区组位置基数。
     */
    public long countSubjectsAtSite(String trialId, String siteCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM trial_subject WHERE trial_id = ? AND site_code = ?",
                Long.class, trialId, siteCode);
        return count == null ? 0L : count;
    }

    // ---------------- 受试者 ----------------

    private static final RowMapper<SubjectRow> SUBJECT_MAPPER = (rs, n) -> new SubjectRow(
            rs.getLong("id"),
            rs.getString("trial_id"),
            rs.getString("site_code"),
            rs.getString("subject_screen_no"),
            rs.getLong("randomization_no"),
            rs.getString("blinded_group_code"),
            rs.getString("assigned_group"),
            rs.getTimestamp("consent_at_utc").toInstant(),
            rs.getTimestamp("enrolled_at_utc").toInstant(),
            (Timestamp) rs.getObject("unblinded_at_utc") == null
                    ? null : rs.getTimestamp("unblinded_at_utc").toInstant(),
            rs.getString("status"));

    public void insertSubject(SubjectRow row) {
        jdbc.update("INSERT INTO trial_subject (trial_id, site_code, subject_screen_no, randomization_no, "
                + "blinded_group_code, assigned_group, consent_at_utc, enrolled_at_utc, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.trialId(), row.siteCode(), row.subjectScreenNo(), row.randomizationNo(),
                row.blindedGroupCode(), row.assignedGroup(),
                ts(row.consentAtUtc()), ts(row.enrolledAtUtc()), row.status());
    }

    public Optional<SubjectRow> findSubject(String trialId, String subjectScreenNo) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM trial_subject WHERE trial_id = ? AND subject_screen_no = ?",
                    SUBJECT_MAPPER, trialId, subjectScreenNo));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    public void markSubjectUnblinded(String trialId, String subjectScreenNo, Instant unblindedAt) {
        jdbc.update("UPDATE trial_subject SET status = 'UNBLINDED', unblinded_at_utc = ? "
                + "WHERE trial_id = ? AND subject_screen_no = ? AND status = 'ENROLLED'",
                ts(unblindedAt), trialId, subjectScreenNo);
    }

    // ---------------- 入组幂等记录 ----------------

    private static final RowMapper<EnrollmentRequestRow> ENROLLMENT_REQUEST_MAPPER = (rs, n) ->
            new EnrollmentRequestRow(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("trial_id"),
                    rs.getString("subject_screen_no"),
                    rs.getString("site_code"),
                    rs.getTimestamp("consent_at_utc").toInstant(),
                    rs.getString("fingerprint"),
                    rs.getLong("randomization_no"),
                    rs.getString("blinded_group_code"),
                    rs.getTimestamp("created_at_utc").toInstant());

    public void insertEnrollmentRequest(EnrollmentRequestRow row) {
        jdbc.update("INSERT INTO enrollment_request (request_id, trial_id, subject_screen_no, site_code, "
                + "consent_at_utc, fingerprint, randomization_no, blinded_group_code, created_at_utc) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.trialId(), row.subjectScreenNo(), row.siteCode(),
                ts(row.consentAtUtc()), row.fingerprint(), row.randomizationNo(),
                row.blindedGroupCode(), ts(row.createdAtUtc()));
    }

    public Optional<EnrollmentRequestRow> findEnrollmentRequest(String requestId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM enrollment_request WHERE request_id = ?",
                    ENROLLMENT_REQUEST_MAPPER, requestId));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    // ---------------- 揭盲申请 ----------------

    private static final RowMapper<UnblindingRequestRow> UNBLINDING_MAPPER = (rs, n) ->
            new UnblindingRequestRow(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("trial_id"),
                    rs.getString("subject_screen_no"),
                    rs.getString("status"),
                    rs.getString("confirmer_a"),
                    rs.getTimestamp("confirmed_a_at_utc") == null
                            ? null : rs.getTimestamp("confirmed_a_at_utc").toInstant(),
                    rs.getString("confirmer_b"),
                    rs.getTimestamp("confirmed_b_at_utc") == null
                            ? null : rs.getTimestamp("confirmed_b_at_utc").toInstant(),
                    rs.getTimestamp("completed_at_utc") == null
                            ? null : rs.getTimestamp("completed_at_utc").toInstant(),
                    rs.getTimestamp("created_at_utc").toInstant());

    public void insertUnblindingRequest(UnblindingRequestRow row) {
        jdbc.update("INSERT INTO unblinding_request (request_id, trial_id, subject_screen_no, status, "
                + "confirmer_a, confirmed_a_at_utc, created_at_utc) VALUES (?, ?, ?, 'PENDING', ?, ?, ?)",
                row.requestId(), row.trialId(), row.subjectScreenNo(),
                row.confirmerA(), ts(row.confirmedAAtUtc()), ts(row.createdAtUtc()));
    }

    public Optional<UnblindingRequestRow> findUnblindingByRequestId(String requestId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM unblinding_request WHERE request_id = ?",
                    UNBLINDING_MAPPER, requestId));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    /**
     * 查找该受试者已有的揭盲申请（用于终态返回首次结果、阻断并行申请）。
     */
    public Optional<UnblindingRequestRow> findUnblindingForSubject(String trialId, String subjectScreenNo) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM unblinding_request WHERE trial_id = ? AND subject_screen_no = ? "
                            + "ORDER BY id LIMIT 1",
                    UNBLINDING_MAPPER, trialId, subjectScreenNo));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    public int completeUnblinding(String requestId, String confirmerB, Instant confirmedAt, Instant completedAt) {
        return jdbc.update("UPDATE unblinding_request SET status = 'COMPLETED', confirmer_b = ?, "
                + "confirmed_b_at_utc = ?, completed_at_utc = ? WHERE request_id = ? AND status = 'PENDING'",
                confirmerB, ts(confirmedAt), ts(completedAt), requestId);
    }

    // ---------------- 审计事件 ----------------

    public void insertAuditEvent(String trialId, String subjectScreenNo, String requestId,
                                 String eventType, String actorUserId, String detail, Instant now) {
        jdbc.update("INSERT INTO unblinding_audit_event (trial_id, subject_screen_no, request_id, "
                + "event_type, actor_user_id, detail, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                trialId, subjectScreenNo, requestId, eventType, actorUserId, detail, ts(now));
    }

    private static final RowMapper<AuditEventRow> AUDIT_MAPPER = (rs, n) -> new AuditEventRow(
            rs.getLong("id"),
            rs.getString("trial_id"),
            rs.getString("subject_screen_no"),
            rs.getString("request_id"),
            rs.getString("event_type"),
            rs.getString("actor_user_id"),
            rs.getString("detail"),
            rs.getTimestamp("created_at_utc").toInstant());

    public List<AuditEventRow> listAuditEvents(String trialId, String subjectScreenNo) {
        return jdbc.query("SELECT * FROM unblinding_audit_event WHERE trial_id = ? AND subject_screen_no = ? "
                + "ORDER BY id", AUDIT_MAPPER, trialId, subjectScreenNo);
    }

    // ---------------- 授权用户 ----------------

    public boolean isAuthorizedUserEnabled(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM authorized_user WHERE user_id = ? AND enabled = 1",
                Integer.class, userId);
        return count != null && count > 0;
    }

    // ---------------- 访视 ----------------

    public void insertVisit(VisitRow row) {
        jdbc.update("INSERT INTO visit_record (trial_id, subject_screen_no, visit_at_utc, visit_type, "
                + "measure_value, excluded_from_main_analysis, exclusion_reason, created_at_utc) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.trialId(), row.subjectScreenNo(), ts(row.visitAtUtc()), row.visitType(),
                row.measureValue(), row.excludedFromMainAnalysis() ? 1 : 0,
                row.exclusionReason(), ts(row.createdAtUtc()));
    }

    /**
     * 揭盲生效时：把访视时刻不早于“揭盲当日 UTC 0 点”的访视统一标记为排除出主要分析集。
     * 日界换算在应用层按 UTC 完成，避免 JVM 默认时区造成的日期偏差。
     */
    public int markVisitsExcludedSince(String trialId, String subjectScreenNo,
                                       Instant utcDayBoundary, String reason) {
        return jdbc.update("UPDATE visit_record SET excluded_from_main_analysis = 1, exclusion_reason = ? "
                        + "WHERE trial_id = ? AND subject_screen_no = ? "
                        + "AND visit_at_utc >= ? AND excluded_from_main_analysis = 0",
                reason, trialId, subjectScreenNo, ts(utcDayBoundary));
    }

    private static final RowMapper<VisitRow> VISIT_MAPPER = (rs, n) -> new VisitRow(
            rs.getLong("id"),
            rs.getString("trial_id"),
            rs.getString("subject_screen_no"),
            rs.getTimestamp("visit_at_utc").toInstant(),
            rs.getString("visit_type"),
            (Double) rs.getObject("measure_value"),
            rs.getInt("excluded_from_main_analysis") == 1,
            rs.getString("exclusion_reason"),
            rs.getTimestamp("created_at_utc").toInstant());

    public List<VisitRow> listVisits(String trialId, String subjectScreenNo) {
        return jdbc.query("SELECT * FROM visit_record WHERE trial_id = ? AND subject_screen_no = ? ORDER BY visit_at_utc",
                VISIT_MAPPER, trialId, subjectScreenNo);
    }
}
