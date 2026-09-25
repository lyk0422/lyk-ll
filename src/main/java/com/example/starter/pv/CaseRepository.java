package com.example.starter.pv;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 个例表数据访问。所有 SQL 参数化；并发创建依赖 uk_pv_case_active 唯一索引兜底。
 */
@Repository
public class CaseRepository {

    private static final String COLUMNS =
            "id, case_no, patient_id, drug_name, adverse_event, seriousness, awareness_date, due_at,"
            + " status, initial_submitted_at, last_follow_up_at, closed_at, linked_case_no, version";

    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PvCase> MAPPER = (rs, rowNum) -> map(rs);

    private static PvCase map(ResultSet rs) throws SQLException {
        Timestamp initial = rs.getTimestamp("initial_submitted_at");
        Timestamp lastFollowUp = rs.getTimestamp("last_follow_up_at");
        Timestamp closed = rs.getTimestamp("closed_at");
        return new PvCase(
                rs.getLong("id"),
                rs.getString("case_no"),
                rs.getString("patient_id"),
                rs.getString("drug_name"),
                rs.getString("adverse_event"),
                Seriousness.valueOf(rs.getString("seriousness")),
                rs.getDate("awareness_date").toLocalDate(),
                rs.getTimestamp("due_at").toInstant(),
                CaseStatus.valueOf(rs.getString("status")),
                initial == null ? null : initial.toInstant(),
                lastFollowUp == null ? null : lastFollowUp.toInstant(),
                closed == null ? null : closed.toInstant(),
                rs.getString("linked_case_no"),
                rs.getInt("version"));
    }

    /**
     * 新建个例（活动标记为 A）。唯一索引冲突时抛出 DuplicateKeyException，由上层转为并入随访。
     */
    public PvCase insert(PvCase c, java.time.Instant now) {
        String sql = "INSERT INTO pv_case (case_no, patient_id, drug_name, adverse_event, seriousness,"
                + " awareness_date, due_at, status, initial_submitted_at, last_follow_up_at, closed_at,"
                + " linked_case_no, active_marker, version, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'A', ?, ?, ?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.caseNo());
            ps.setString(2, c.patientId());
            ps.setString(3, c.drugName());
            ps.setString(4, c.adverseEvent());
            ps.setString(5, c.seriousness().name());
            ps.setDate(6, java.sql.Date.valueOf(c.awarenessDate()));
            ps.setTimestamp(7, Timestamp.from(c.dueAt()));
            ps.setString(8, c.status().name());
            ps.setTimestamp(9, Timestamp.from(c.initialSubmittedAt()));
            ps.setNull(10, java.sql.Types.TIMESTAMP);
            ps.setNull(11, java.sql.Types.TIMESTAMP);
            if (c.linkedCaseNo() == null) {
                ps.setString(12, null);
            } else {
                ps.setString(12, c.linkedCaseNo());
            }
            ps.setInt(13, c.version());
            ps.setTimestamp(14, Timestamp.from(now));
            ps.setTimestamp(15, Timestamp.from(now));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return new PvCase(key == null ? null : key.longValue(), c.caseNo(), c.patientId(), c.drugName(),
                c.adverseEvent(), c.seriousness(), c.awarenessDate(), c.dueAt(), c.status(),
                c.initialSubmittedAt(), null, null, c.linkedCaseNo(), c.version());
    }

    /**
     * 按患者+药品+事件查询活动个例。
     */
    public Optional<PvCase> findActiveByTriple(String patientId, String drugName, String adverseEvent) {
        List<PvCase> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_case"
                + " WHERE patient_id = ? AND drug_name = ? AND adverse_event = ? AND active_marker = 'A'",
                MAPPER, patientId, drugName, adverseEvent);
        return list.stream().findFirst();
    }

    /**
     * 按患者+药品+事件查询最近关闭的个例（用于新建关联个例时引用原编号）。
     */
    public Optional<PvCase> findLatestClosedByTriple(String patientId, String drugName, String adverseEvent) {
        List<PvCase> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_case"
                + " WHERE patient_id = ? AND drug_name = ? AND adverse_event = ? AND active_marker IS NULL"
                + " ORDER BY closed_at DESC, id DESC LIMIT 1",
                MAPPER, patientId, drugName, adverseEvent);
        return list.stream().findFirst();
    }

    /**
     * 按个例编号查询。
     */
    public Optional<PvCase> findByCaseNo(String caseNo) {
        List<PvCase> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_case WHERE case_no = ?", MAPPER, caseNo);
        return list.stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<PvCase> findById(long id) {
        List<PvCase> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_case WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    /**
     * 按个例编号查询并加行锁（须在事务内使用）。
     */
    public Optional<PvCase> findByCaseNoForUpdate(String caseNo) {
        List<PvCase> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_case WHERE case_no = ? FOR UPDATE", MAPPER, caseNo);
        return list.stream().findFirst();
    }

    /**
     * 按主键查询并加行锁（须在事务内使用）。
     */
    public Optional<PvCase> findByIdForUpdate(long id) {
        List<PvCase> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_case WHERE id = ? FOR UPDATE", MAPPER, id);
        return list.stream().findFirst();
    }

    /**
     * 查询当前可递交（已初报且未关闭）的个例并加行锁，作为递交批次快照（须在事务内使用）。
     */
    public List<PvCase> findSubmittableForUpdate() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM pv_case"
                + " WHERE status IN ('INITIAL_SUBMITTED', 'FOLLOW_UP_SUBMITTED') ORDER BY id FOR UPDATE",
                MAPPER);
    }

    /**
     * 乐观锁更新：仅当版本匹配时写入，返回受影响行数。
     */
    public int updateWithVersion(PvCase c, java.time.Instant now) {        return jdbc.update(
                "UPDATE pv_case SET status = ?, initial_submitted_at = ?, last_follow_up_at = ?,"
                + " closed_at = ?, active_marker = ?, version = ?, updated_at = ?"
                + " WHERE id = ? AND version = ?",
                c.status().name(),
                c.initialSubmittedAt() == null ? null : Timestamp.from(c.initialSubmittedAt()),
                c.lastFollowUpAt() == null ? null : Timestamp.from(c.lastFollowUpAt()),
                c.closedAt() == null ? null : Timestamp.from(c.closedAt()),
                c.status() == CaseStatus.CLOSED ? null : "A",
                c.version(),
                Timestamp.from(now),
                c.id(),
                c.version() - 1);
    }

    /**
     * 写入正式个例编号（插入后按自增主键生成，保证唯一且可读）。
     */
    public void assignCaseNo(long id, String caseNo) {
        jdbc.update("UPDATE pv_case SET case_no = ? WHERE id = ?", caseNo, id);
    }
}
