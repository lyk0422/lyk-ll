package com.example.starter.pv.data.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.example.starter.pv.data.CasePo;
import com.example.starter.pv.data.CaseRepository;
import com.example.starter.pv.domain.CaseStatus;
import com.example.starter.pv.domain.Seriousness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * pv_case 的 JDBC 实现（H2 MODE=MySQL）。
 */
@Repository
public class JdbcCaseRepository implements CaseRepository {

    private static final String COLUMNS = """
            id, case_no, patient_code, patient_name, drug_code, drug_name, reaction_event,
            seriousness, status, awareness_date, initial_submitted_at, initial_due_date,
            on_time, late_reason, latest_follow_up_date, follow_up_count, related_case_no,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(CasePo po) {
        jdbcTemplate.update("""
                INSERT INTO pv_case (case_no, patient_code, patient_name, drug_code, drug_name,
                    reaction_event, seriousness, status, awareness_date, initial_submitted_at,
                    initial_due_date, on_time, late_reason, latest_follow_up_date, follow_up_count,
                    related_case_no, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                po.caseNo(), po.patientCode(), po.patientName(), po.drugCode(), po.drugName(),
                po.reactionEvent(), po.seriousness().name(), po.status().name(),
                po.awarenessDate(), Timestamp.from(po.initialSubmittedAt()),
                po.initialDueDate(), po.onTime(), po.lateReason(),
                po.latestFollowUpDate(), po.followUpCount(), po.relatedCaseNo(),
                Timestamp.from(po.createdAt()), Timestamp.from(po.updatedAt()));
    }

    @Override
    public Optional<CasePo> findByCaseNo(String caseNo) {
        return queryOne("SELECT " + COLUMNS + " FROM pv_case WHERE case_no = ?", caseNo);
    }

    @Override
    public Optional<CasePo> lockByCaseNo(String caseNo) {
        return queryOne(
                "SELECT " + COLUMNS + " FROM pv_case WHERE case_no = ? FOR UPDATE", caseNo);
    }

    private Optional<CasePo> queryOne(String sql, String caseNo) {
        List<CasePo> list = jdbcTemplate.query(sql, new CaseRowMapper(), caseNo);
        return list.stream().findFirst();
    }

    @Override
    public Optional<CasePo> findActive(String patientCode, String drugCode, String reactionEvent) {
        List<CasePo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM pv_case WHERE status = 'REPORTED' AND patient_code = ?"
                        + " AND drug_code = ? AND reaction_event = ?",
                new CaseRowMapper(), patientCode, drugCode, reactionEvent);
        return list.stream().findFirst();
    }

    @Override
    public Optional<CasePo> findLatestByDedup(String patientCode, String drugCode,
                                              String reactionEvent) {
        List<CasePo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM pv_case WHERE patient_code = ? AND drug_code = ?"
                        + " AND reaction_event = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                new CaseRowMapper(), patientCode, drugCode, reactionEvent);
        return list.stream().findFirst();
    }

    @Override
    public void applyFollowUp(String caseNo, LocalDate newFollowUpDate,
                              int followUpCount, Instant updatedAt) {
        // 最近随访日取既有值与本次信息日的较晚者，保证乱序到达的并入信息不回退日期
        int rows = jdbcTemplate.update("""
                UPDATE pv_case
                SET latest_follow_up_date = CASE
                        WHEN latest_follow_up_date IS NULL OR ? > latest_follow_up_date
                        THEN ? ELSE latest_follow_up_date END,
                    follow_up_count = ?,
                    updated_at = ?
                WHERE case_no = ? AND status = 'REPORTED'
                """,
                newFollowUpDate, newFollowUpDate, followUpCount,
                Timestamp.from(updatedAt), caseNo);
        if (rows != 1) {
            throw new IllegalStateException("并入信息更新失败，个例不存在或已关闭: " + caseNo);
        }
    }

    @Override
    public void markClosed(String caseNo, Instant updatedAt) {
        int rows = jdbcTemplate.update(
                "UPDATE pv_case SET status = 'CLOSED', updated_at = ? WHERE case_no = ?",
                Timestamp.from(updatedAt), caseNo);
        if (rows != 1) {
            throw new IllegalStateException("关闭更新失败，个例不存在: " + caseNo);
        }
    }

    @Override
    public List<CasePo> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM pv_case ORDER BY case_no ASC",
                new CaseRowMapper());
    }

    @Override
    public List<CasePo> findActiveAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM pv_case WHERE status = 'REPORTED' ORDER BY case_no ASC",
                new CaseRowMapper());
    }

    @Override
    public List<CasePo> lockActiveAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM pv_case WHERE status = 'REPORTED' ORDER BY case_no ASC FOR UPDATE",
                new CaseRowMapper());
    }

    @Override
    public List<CasePo> findActiveByCaseNos(List<String> caseNos) {
        return queryActive(caseNos, false);
    }

    @Override
    public List<CasePo> lockActiveByCaseNos(List<String> caseNos) {
        return queryActive(caseNos, true);
    }

    private List<CasePo> queryActive(List<String> caseNos, boolean forUpdate) {
        if (caseNos == null || caseNos.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", caseNos.stream().map(c -> "?").toList());
        String suffix = forUpdate ? " FOR UPDATE" : "";
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM pv_case WHERE status = 'REPORTED' AND case_no IN ("
                        + placeholders + ") ORDER BY case_no ASC" + suffix,
                new CaseRowMapper(), caseNos.toArray());
    }

    /**
     * 个例行映射器。
     */
    static class CaseRowMapper implements RowMapper<CasePo> {
        @Override
        public CasePo mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CasePo(
                    rs.getLong("id"),
                    rs.getString("case_no"),
                    rs.getString("patient_code"),
                    rs.getString("patient_name"),
                    rs.getString("drug_code"),
                    rs.getString("drug_name"),
                    rs.getString("reaction_event"),
                    Seriousness.valueOf(rs.getString("seriousness")),
                    CaseStatus.valueOf(rs.getString("status")),
                    rs.getObject("awareness_date", LocalDate.class),
                    rs.getTimestamp("initial_submitted_at").toInstant(),
                    rs.getObject("initial_due_date", LocalDate.class),
                    rs.getBoolean("on_time"),
                    rs.getString("late_reason"),
                    rs.getObject("latest_follow_up_date", LocalDate.class),
                    rs.getInt("follow_up_count"),
                    rs.getString("related_case_no"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
