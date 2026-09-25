package com.example.starter.damage.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.damage.repo.po.DamageCasePo;

/** 残损案主表数据访问；UNIQUE(active_flag) 承担同箱同时刻唯一在办约束。 */
@Repository
public class DamageCaseRepository {

    private static final String COLUMNS = """
            id, case_no, container_no, container_value, status, active_flag,
            current_estimate_id, approved_at_utc, valid_until_utc, repair_attempts,
            closed_at_utc, close_outcome, created_at_utc, updated_at_utc
            """;

    private final JdbcTemplate jdbc;

    public DamageCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String caseNo, String containerNo, java.math.BigDecimal containerValue,
                       String activeFlag, long nowUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO damage_case
                        (case_no, container_no, container_value, status, active_flag,
                         repair_attempts, created_at_utc, updated_at_utc)
                    VALUES (?, ?, ?, 'REGISTERED', ?, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, caseNo);
            ps.setString(2, containerNo);
            ps.setBigDecimal(3, containerValue);
            ps.setString(4, activeFlag);
            ps.setLong(5, nowUtc);
            ps.setLong(6, nowUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<DamageCasePo> findByCaseNo(String caseNo) {
        List<DamageCasePo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM damage_case WHERE case_no = ?",
                (rs, n) -> map(rs), caseNo);
        return list.stream().findFirst();
    }

    public Optional<DamageCasePo> findActiveByContainer(String containerNo) {
        List<DamageCasePo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM damage_case WHERE active_flag = ?",
                (rs, n) -> map(rs), containerNo);
        return list.stream().findFirst();
    }

    public void updateStatus(long caseId, String status, long nowUtc) {
        jdbc.update("UPDATE damage_case SET status = ?, updated_at_utc = ? WHERE id = ?",
                status, nowUtc, caseId);
    }

    public void updateCurrentEstimate(long caseId, long estimateId, String status, long nowUtc) {
        jdbc.update("UPDATE damage_case SET current_estimate_id = ?, status = ?, updated_at_utc = ? WHERE id = ?",
                estimateId, status, nowUtc, caseId);
    }

    public void updateApproval(long caseId, long approvedAtUtc, long validUntilUtc,
                               int repairAttempts, String status, long nowUtc) {
        jdbc.update("""
                UPDATE damage_case
                SET approved_at_utc = ?, valid_until_utc = ?, repair_attempts = ?,
                    status = ?, updated_at_utc = ?
                WHERE id = ?
                """, approvedAtUtc, validUntilUtc, repairAttempts, status, nowUtc, caseId);
    }

    /** 结案：终态、清空在办标志，放箱/全损与案件关闭同事务完成。 */
    public void close(long caseId, String status, String closeOutcome, long closedAtUtc) {
        jdbc.update("""
                UPDATE damage_case
                SET status = ?, close_outcome = ?, closed_at_utc = ?, active_flag = NULL,
                    updated_at_utc = ?
                WHERE id = ?
                """, status, closeOutcome, closedAtUtc, closedAtUtc, caseId);
    }

    private DamageCasePo map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DamageCasePo(
                rs.getLong("id"),
                rs.getString("case_no"),
                rs.getString("container_no"),
                rs.getBigDecimal("container_value"),
                rs.getString("status"),
                rs.getString("active_flag"),
                (Long) rs.getObject("current_estimate_id"),
                (Long) rs.getObject("approved_at_utc"),
                (Long) rs.getObject("valid_until_utc"),
                rs.getInt("repair_attempts"),
                (Long) rs.getObject("closed_at_utc"),
                rs.getString("close_outcome"),
                (Long) rs.getObject("created_at_utc"),
                (Long) rs.getObject("updated_at_utc"));
    }
}
