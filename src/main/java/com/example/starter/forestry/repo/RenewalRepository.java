package com.example.starter.forestry.repo;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.forestry.po.RenewalPO;

/**
 * 迹地更新义务仓储。
 */
@Repository
public class RenewalRepository {

    private final JdbcTemplate jdbc;

    public RenewalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            id, application_id, operator_id, status, deadline, replant_deadline,
            qualified_rate, accepted_date, replant_accepted_date, created_date
            """;

    private RenewalPO mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal rate = rs.getBigDecimal("qualified_rate");
        return new RenewalPO(
                rs.getString("id"),
                rs.getString("application_id"),
                rs.getString("operator_id"),
                rs.getString("status"),
                rs.getDate("deadline").toLocalDate(),
                rs.getDate("replant_deadline") == null ? null : rs.getDate("replant_deadline").toLocalDate(),
                rate,
                rs.getDate("accepted_date") == null ? null : rs.getDate("accepted_date").toLocalDate(),
                rs.getDate("replant_accepted_date") == null ? null
                        : rs.getDate("replant_accepted_date").toLocalDate(),
                rs.getDate("created_date").toLocalDate());
    }

    public void insert(RenewalPO po) {
        jdbc.update("""
                        INSERT INTO renewal_obligation
                            (id, application_id, operator_id, status, deadline, replant_deadline,
                             qualified_rate, accepted_date, replant_accepted_date, created_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                po.id(), po.applicationId(), po.operatorId(), po.status(), Date.valueOf(po.deadline()),
                po.replantDeadline() == null ? null : Date.valueOf(po.replantDeadline()),
                po.qualifiedRate(),
                po.acceptedDate() == null ? null : Date.valueOf(po.acceptedDate()),
                po.replantAcceptedDate() == null ? null : Date.valueOf(po.replantAcceptedDate()),
                Date.valueOf(po.createdDate()));
    }

    public RenewalPO findByApplicationId(String applicationId) {
        List<RenewalPO> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM renewal_obligation WHERE application_id = ?",
                (rs, n) -> mapRow(rs), applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 首次验收不合格：进入补植，记录合格率、验收日期与补植截止日。
     */
    public void markReplanting(String applicationId, BigDecimal rate, LocalDate acceptedDate,
                               LocalDate replantDeadline) {
        jdbc.update("""
                        UPDATE renewal_obligation
                           SET status = 'REPLANTING', qualified_rate = ?, accepted_date = ?,
                               replant_deadline = ?
                         WHERE application_id = ? AND status = 'PENDING'
                        """,
                rate, Date.valueOf(acceptedDate), Date.valueOf(replantDeadline), applicationId);
    }

    /**
     * 首次验收通过（仅 PENDING 可转）。
     */
    public int markPassedFromPending(String applicationId, BigDecimal rate, LocalDate acceptedDate) {
        return jdbc.update("""
                        UPDATE renewal_obligation
                           SET status = 'PASSED', qualified_rate = ?, accepted_date = ?
                         WHERE application_id = ? AND status = 'PENDING'
                        """,
                rate, Date.valueOf(acceptedDate), applicationId);
    }

    /**
     * 补植验收：通过或失败（仅 REPLANTING 可转）。
     */
    public int completeReplant(String applicationId, String status, BigDecimal rate,
                               LocalDate replantAcceptedDate) {
        return jdbc.update("""
                        UPDATE renewal_obligation
                           SET status = ?, qualified_rate = ?, replant_accepted_date = ?
                         WHERE application_id = ? AND status = 'REPLANTING'
                        """,
                status, rate, Date.valueOf(replantAcceptedDate), applicationId);
    }
}
