package com.example.starter.survey.repo;

import com.example.starter.survey.domain.DefectRow;
import com.example.starter.survey.domain.SurveyEventRow;
import com.example.starter.survey.domain.SurveyRow;
import com.example.starter.survey.model.SurveyEnums.DefectStatus;
import com.example.starter.survey.model.SurveyEnums.Severity;
import com.example.starter.survey.model.SurveyEnums.SurveyStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

/**
 * 检验单、检验事件链、缺陷链数据访问。
 */
@Repository
public class SurveyRepo {

    private final JdbcTemplate jdbc;

    public SurveyRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入进行中检验单（active_slot=1）；并发下唯一索引 uk_survey_active 保证唯一受理。 */
    public long insertSurvey(String applyRequestId, long shipId, String typeCode,
                             LocalDate anniversaryDate, LocalDate windowStart, LocalDate windowEnd) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO survey (apply_request_id, ship_id, type_code, status, "
                            + "anniversary_date, window_start, window_end, extension_months, "
                            + "applied_at, active_slot, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setString(1, applyRequestId);
            ps.setLong(2, shipId);
            ps.setString(3, typeCode);
            ps.setString(4, SurveyStatus.APPLIED.name());
            setDate(ps, 5, anniversaryDate);
            setDate(ps, 6, windowStart);
            setDate(ps, 7, windowEnd);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public SurveyRow findSurvey(long id) {
        List<SurveyRow> rows = jdbc.query(
                "SELECT * FROM survey WHERE id = ?", this::mapSurvey, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级悲观锁：发证等终态操作在事务内串行化（H2/MySQL 均支持 FOR UPDATE）。 */
    public SurveyRow lockSurvey(long id) {
        List<SurveyRow> rows = jdbc.query(
                "SELECT * FROM survey WHERE id = ? FOR UPDATE", this::mapSurvey, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SurveyRow findActiveSurvey(long shipId, String typeCode) {
        List<SurveyRow> rows = jdbc.query(
                "SELECT * FROM survey WHERE ship_id = ? AND type_code = ? AND active_slot = 1",
                this::mapSurvey, shipId, typeCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 登轮：记录完成日、窗口字段，并转移状态。 */
    public void markBoarded(long surveyId, SurveyStatus status, LocalDate boardedDate,
                            LocalDate windowStart, LocalDate windowEnd) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE survey SET status = ?, boarded_at = ?, window_start = ?, "
                            + "window_end = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?");
            ps.setString(1, status.name());
            ps.setDate(2, Date.valueOf(boardedDate));
            setDate(ps, 3, windowStart);
            setDate(ps, 4, windowEnd);
            ps.setLong(5, surveyId);
            return ps;
        });
    }

    public void updateStatus(long surveyId, SurveyStatus status) {
        jdbc.update("UPDATE survey SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status.name(), surveyId);
    }

    /** 展期批准后同步已批准展期月数（可复算字段）。 */
    public void updateExtensionMonths(long surveyId, int months) {
        jdbc.update("UPDATE survey SET extension_months = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                months, surveyId);
    }

    /** 全量检验单 ID（复算缺陷链用）。 */
    public List<Long> findAllSurveyIds() {
        return jdbc.queryForList("SELECT id FROM survey ORDER BY id", Long.class);
    }

    /** 发证终态：active_slot 置 NULL，释放“同船同类型进行中”槽位。 */
    public void markIssued(long surveyId, LocalDate issuedDate) {
        jdbc.update("UPDATE survey SET status = ?, issued_at = ?, active_slot = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                SurveyStatus.ISSUED.name(), Date.valueOf(issuedDate), surveyId);
    }

    public void addEvent(long surveyId, String eventType, LocalDate eventDate, String detail) {
        jdbc.update("INSERT INTO survey_event (survey_id, event_type, event_date, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                surveyId, eventType, eventDate == null ? null : Date.valueOf(eventDate), detail);
    }

    public List<SurveyEventRow> findEvents(long surveyId) {
        return jdbc.query("SELECT * FROM survey_event WHERE survey_id = ? ORDER BY id",
                this::mapEvent, surveyId);
    }

    public long insertDefect(long surveyId, Severity severity, String description,
                             DefectStatus status, LocalDate foundDate, LocalDate dueDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO defect (survey_id, severity, description, status, found_date, due_date) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", new String[]{"id"});
            ps.setLong(1, surveyId);
            ps.setString(2, severity.name());
            ps.setString(3, description);
            ps.setString(4, status.name());
            ps.setDate(5, Date.valueOf(foundDate));
            setDate(ps, 6, dueDate);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public DefectRow findDefect(long id) {
        List<DefectRow> rows = jdbc.query("SELECT * FROM defect WHERE id = ?", this::mapDefect, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级悲观锁：整改/复查并发提交串行化。 */
    public DefectRow lockDefect(long id) {
        List<DefectRow> rows = jdbc.query("SELECT * FROM defect WHERE id = ? FOR UPDATE",
                this::mapDefect, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DefectRow> findDefectsBySurvey(long surveyId) {
        return jdbc.query("SELECT * FROM defect WHERE survey_id = ? ORDER BY id",
                this::mapDefect, surveyId);
    }

    /** 整改上报：一般缺陷进入 RECTIFIED 待复查；重大缺陷整改即 ACCEPTED。 */
    public void markRectified(long defectId, DefectStatus status, LocalDate rectifiedDate,
                              LocalDate acceptedDate) {
        jdbc.update("UPDATE defect SET status = ?, rectified_date = ?, accepted_date = ? WHERE id = ?",
                status.name(), Date.valueOf(rectifiedDate),
                acceptedDate == null ? null : Date.valueOf(acceptedDate), defectId);
    }

    /** 复查受理。 */
    public void markRecheckAccepted(long defectId, LocalDate submitDate, LocalDate acceptedDate) {
        jdbc.update("UPDATE defect SET status = ?, recheck_submit_date = ?, accepted_date = ? WHERE id = ?",
                DefectStatus.ACCEPTED.name(), Date.valueOf(submitDate),
                Date.valueOf(acceptedDate), defectId);
    }

    /** 复查逾期。 */
    public void markDefectExpired(long defectId) {
        jdbc.update("UPDATE defect SET status = ? WHERE id = ?",
                DefectStatus.EXPIRED.name(), defectId);
    }

    private SurveyRow mapSurvey(ResultSet rs, int n) throws SQLException {
        Boolean active = rs.getObject("active_slot") == null ? null
                : rs.getInt("active_slot") == 1;
        return new SurveyRow(
                rs.getLong("id"),
                rs.getString("apply_request_id"),
                rs.getLong("ship_id"),
                rs.getString("type_code"),
                SurveyStatus.valueOf(rs.getString("status")),
                rs.getObject("anniversary_date", LocalDate.class),
                rs.getObject("window_start", LocalDate.class),
                rs.getObject("window_end", LocalDate.class),
                rs.getInt("extension_months"),
                rs.getObject("applied_at", java.time.LocalDateTime.class),
                rs.getObject("boarded_at", LocalDate.class),
                rs.getObject("issued_at", LocalDate.class),
                active,
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getObject("updated_at", java.time.LocalDateTime.class));
    }

    private DefectRow mapDefect(ResultSet rs, int n) throws SQLException {
        return new DefectRow(
                rs.getLong("id"),
                rs.getLong("survey_id"),
                Severity.valueOf(rs.getString("severity")),
                rs.getString("description"),
                DefectStatus.valueOf(rs.getString("status")),
                rs.getObject("found_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class),
                rs.getObject("rectified_date", LocalDate.class),
                rs.getObject("recheck_submit_date", LocalDate.class),
                rs.getObject("accepted_date", LocalDate.class));
    }

    private SurveyEventRow mapEvent(ResultSet rs, int n) throws SQLException {
        return new SurveyEventRow(
                rs.getLong("id"),
                rs.getLong("survey_id"),
                rs.getString("event_type"),
                rs.getObject("event_date", LocalDate.class),
                rs.getString("detail"),
                rs.getObject("created_at", java.time.LocalDateTime.class));
    }

    /** 可空 DATE 参数绑定（H2 MODE=MySQL 下不允许 Date.valueOf(null)）。 */
    private static void setDate(PreparedStatement ps, int index, LocalDate value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(value));
        }
    }
}
