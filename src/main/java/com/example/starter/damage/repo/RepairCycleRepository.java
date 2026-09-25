package com.example.starter.damage.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.damage.repo.po.RepairCyclePo;

/** 修理链数据访问：批复、完工、复检逐环更新。 */
@Repository
public class RepairCycleRepository {

    private final JdbcTemplate jdbc;

    public RepairCycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertApproved(long caseId, int attemptNo, long estimateId,
                               String approveRequestId, long approvedAtUtc, long validUntilUtc,
                               long nowUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO repair_cycle
                        (case_id, attempt_no, estimate_id, approve_request_id, approved_at_utc,
                         valid_until_utc, status, created_at_utc)
                    VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, caseId);
            ps.setInt(2, attemptNo);
            ps.setLong(3, estimateId);
            ps.setString(4, approveRequestId);
            ps.setLong(5, approvedAtUtc);
            ps.setLong(6, validUntilUtc);
            ps.setLong(7, nowUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void markCompleted(long cycleId, String completeRequestId, long completedAtUtc, String status) {
        jdbc.update("""
                UPDATE repair_cycle
                SET complete_request_id = ?, completed_at_utc = ?, status = ?
                WHERE id = ?
                """, completeRequestId, completedAtUtc, status, cycleId);
    }

    public void markReinspected(long cycleId, String reinspectRequestId, long reinspectedAtUtc,
                                String reinspectResult, String status) {
        jdbc.update("""
                UPDATE repair_cycle
                SET reinspect_request_id = ?, reinspected_at_utc = ?,
                    reinspect_result = ?, status = ?
                WHERE id = ?
                """, reinspectRequestId, reinspectedAtUtc, reinspectResult, status, cycleId);
    }

    public RepairCyclePo findByCaseAndAttempt(long caseId, int attemptNo) {
        List<RepairCyclePo> list = jdbc.query("""
                SELECT id, case_id, attempt_no, estimate_id, approve_request_id, approved_at_utc,
                       valid_until_utc, complete_request_id, completed_at_utc,
                       reinspect_request_id, reinspected_at_utc, reinspect_result,
                       status, created_at_utc
                FROM repair_cycle WHERE case_id = ? AND attempt_no = ?
                """, (rs, n) -> new RepairCyclePo(
                rs.getLong("id"),
                rs.getLong("case_id"),
                rs.getInt("attempt_no"),
                rs.getLong("estimate_id"),
                rs.getString("approve_request_id"),
                (Long) rs.getObject("approved_at_utc"),
                (Long) rs.getObject("valid_until_utc"),
                rs.getString("complete_request_id"),
                (Long) rs.getObject("completed_at_utc"),
                rs.getString("reinspect_request_id"),
                (Long) rs.getObject("reinspected_at_utc"),
                rs.getString("reinspect_result"),
                rs.getString("status"),
                (Long) rs.getObject("created_at_utc")), caseId, attemptNo);
        return list.stream().findFirst().orElse(null);
    }

    /** 既往复检不合格次数（逾期重做不计返修名额，仅 FAIL 计入）。 */
    public int countFailByCaseId(long caseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_cycle WHERE case_id = ? AND reinspect_result = 'FAIL'",
                Integer.class, caseId);
        return count == null ? 0 : count;
    }

    public List<RepairCyclePo> findByCaseId(long caseId) {        return jdbc.query("""
                SELECT id, case_id, attempt_no, estimate_id, approve_request_id, approved_at_utc,
                       valid_until_utc, complete_request_id, completed_at_utc,
                       reinspect_request_id, reinspected_at_utc, reinspect_result,
                       status, created_at_utc
                FROM repair_cycle WHERE case_id = ? ORDER BY attempt_no
                """, (rs, n) -> new RepairCyclePo(
                rs.getLong("id"),
                rs.getLong("case_id"),
                rs.getInt("attempt_no"),
                rs.getLong("estimate_id"),
                rs.getString("approve_request_id"),
                (Long) rs.getObject("approved_at_utc"),
                (Long) rs.getObject("valid_until_utc"),
                rs.getString("complete_request_id"),
                (Long) rs.getObject("completed_at_utc"),
                rs.getString("reinspect_request_id"),
                (Long) rs.getObject("reinspected_at_utc"),
                rs.getString("reinspect_result"),
                rs.getString("status"),
                (Long) rs.getObject("created_at_utc")), caseId);
    }
}
