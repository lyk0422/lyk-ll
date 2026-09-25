package com.example.starter.survey.repo;

import com.example.starter.survey.domain.ExtensionRow;
import com.example.starter.survey.model.SurveyEnums.ExtensionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 展期申请数据访问。uk_extension_cert (certificate_id, approved_flag) 保证一证至多批准一次。
 */
@Repository
public class ExtensionRepo {

    private final JdbcTemplate jdbc;

    public ExtensionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertPending(String requestId, long shipId, String typeCode, long certificateId,
                              int months) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO extension_application (request_id, ship_id, type_code, "
                            + "certificate_id, months, status, applied_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, shipId);
            ps.setString(3, typeCode);
            ps.setLong(4, certificateId);
            ps.setInt(5, months);
            ps.setString(6, ExtensionStatus.PENDING.name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public ExtensionRow findById(long id) {
        List<ExtensionRow> rows = jdbc.query(
                "SELECT * FROM extension_application WHERE id = ?", this::map, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级悲观锁：并发批准串行化，后到者读到 APPROVED 幂等返回。 */
    public ExtensionRow lockById(long id) {
        List<ExtensionRow> rows = jdbc.query(
                "SELECT * FROM extension_application WHERE id = ? FOR UPDATE", this::map, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ExtensionRow findApprovedByCertificate(long certificateId) {
        List<ExtensionRow> rows = jdbc.query(
                "SELECT * FROM extension_application WHERE certificate_id = ? AND approved_flag = 1",
                this::map, certificateId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ExtensionRow> findPending(long shipId, String typeCode) {
        return jdbc.query(
                "SELECT * FROM extension_application WHERE ship_id = ? AND type_code = ? "
                        + "AND status = 'PENDING' ORDER BY id",
                this::map, shipId, typeCode);
    }

    /** 批准：与证书有效期更新同一事务，approved_flag=1 触发唯一约束兜底。 */
    public void markApproved(long id, String approveRequestId, LocalDate oldValidUntil,
                             LocalDate newValidUntil) {
        jdbc.update(
                "UPDATE extension_application SET status = ?, approve_request_id = ?, "
                        + "old_valid_until = ?, new_valid_until = ?, decided_at = CURRENT_TIMESTAMP, "
                        + "approved_flag = 1 WHERE id = ?",
                ExtensionStatus.APPROVED.name(), approveRequestId,
                Date.valueOf(oldValidUntil), Date.valueOf(newValidUntil), id);
    }

    public void markRejected(long id, String approveRequestId) {
        jdbc.update(
                "UPDATE extension_application SET status = ?, approve_request_id = ?, "
                        + "decided_at = CURRENT_TIMESTAMP WHERE id = ?",
                ExtensionStatus.REJECTED.name(), approveRequestId, id);
    }

    private ExtensionRow map(ResultSet rs, int n) throws SQLException {
        return new ExtensionRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("approve_request_id"),
                rs.getLong("ship_id"),
                rs.getString("type_code"),
                rs.getLong("certificate_id"),
                rs.getInt("months"),
                ExtensionStatus.valueOf(rs.getString("status")),
                rs.getObject("old_valid_until", LocalDate.class),
                rs.getObject("new_valid_until", LocalDate.class),
                rs.getObject("applied_at", java.time.LocalDateTime.class),
                rs.getObject("decided_at", java.time.LocalDateTime.class));
    }
}
