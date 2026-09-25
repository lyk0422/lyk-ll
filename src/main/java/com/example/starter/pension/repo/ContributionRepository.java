package com.example.starter.pension.repo;

import com.example.starter.pension.domain.ContributionType;
import com.example.starter.pension.po.ContributionRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 缴费表数据访问。
 */
@Repository
public class ContributionRepository {

    private static final RowMapper<ContributionRow> ROW_MAPPER = (rs, rowNum) -> new ContributionRow(
            rs.getLong("id"),
            rs.getLong("member_id"),
            ContributionType.valueOf(rs.getString("contribution_type")),
            rs.getLong("amount_cents"),
            rs.getDate("contribution_date").toLocalDate(),
            rs.getString("period"),
            rs.getObject("snapshot_plan_id", Long.class));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public ContributionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 录入一条缴费。
     *
     * @return 缴费主键
     */
    public long insert(long memberId, ContributionType type, long amountCents,
                       LocalDate contributionDate, String period) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO contribution "
                            + "(member_id, contribution_type, amount_cents, contribution_date, period) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, memberId);
            ps.setString(2, type.name());
            ps.setLong(3, amountCents);
            ps.setDate(4, Date.valueOf(contributionDate));
            ps.setString(5, period);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询成员尚未被任何计划快照冻结的缴费（申请受理时据此冻结）。
     */
    public List<ContributionRow> findUnfrozen(long memberId) {
        return jdbcClient.sql("""
                        SELECT id, member_id, contribution_type, amount_cents, contribution_date,
                               period, snapshot_plan_id
                          FROM contribution
                         WHERE member_id = :memberId
                           AND snapshot_plan_id IS NULL
                         ORDER BY id""")
                .param("memberId", memberId)
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * 将指定缴费冻结到某个支付计划快照。
     *
     * @return 实际冻结行数
     */
    public int freeze(Collection<Long> contributionIds, long planId) {
        if (contributionIds.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql("""
                        UPDATE contribution
                           SET snapshot_plan_id = :planId
                         WHERE snapshot_plan_id IS NULL
                           AND id IN (:ids)""")
                .param("planId", planId)
                .param("ids", contributionIds)
                .update();
    }
}
