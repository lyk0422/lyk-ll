package com.example.starter.pension.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.pension.domain.ContributionSource;

/**
 * 缴费流水数据访问。{@code used_by_plan_no} 非空表示该笔缴费已冻结进某计划快照，
 * 结清后的补缴因 used_by_plan_no 仍为空，将被补发流程识别受理。
 */
@Repository
public class ContributionRepository {

    /**
     * 缴费持久化对象。
     */
    public record ContributionPo(
            Long id,
            String memberNo,
            LocalDate contributionDate,
            ContributionSource source,
            long amountCents,
            String usedByPlanNo,
            Instant createdAtUtc) {
    }

    private static final RowMapper<ContributionPo> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public ContributionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一笔合成缴费。
     *
     * @return 自增主键
     */
    public long insert(String memberNo, LocalDate contributionDate, ContributionSource source, long amountCents) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO contribution (member_no, contribution_date, source, amount_cents) "
                            + "VALUES (?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, memberNo);
            ps.setObject(2, contributionDate);
            ps.setString(3, source.name());
            ps.setLong(4, amountCents);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("缴费登记未取得主键");
        }
        return key.longValue();
    }

    /**
     * 查询成员尚未被任何计划受理的缴费（快照未冻结）。
     */
    public List<ContributionPo> findUnusedByMember(String memberNo) {
        return jdbc.query(
                "SELECT id, member_no, contribution_date, source, amount_cents, used_by_plan_no, created_at_utc "
                        + "FROM contribution WHERE member_no = ? AND used_by_plan_no IS NULL ORDER BY id",
                MAPPER, memberNo);
    }

    /**
     * 行级锁定成员尚未受理的缴费，保证并发受理时同一笔缴费只进入一个计划快照。
     */
    public List<ContributionPo> lockUnusedByMember(String memberNo) {
        return jdbc.query(
                "SELECT id, member_no, contribution_date, source, amount_cents, used_by_plan_no, created_at_utc "
                        + "FROM contribution WHERE member_no = ? AND used_by_plan_no IS NULL ORDER BY id FOR UPDATE",
                MAPPER, memberNo);
    }

    /**
     * 按主键查询。
     */
    public ContributionPo findById(long id) {
        return jdbc.queryForObject(
                "SELECT id, member_no, contribution_date, source, amount_cents, used_by_plan_no, created_at_utc "
                        + "FROM contribution WHERE id = ?",
                MAPPER, id);
    }

    /**
     * 将缴费原子地标记为某计划受理；仅当尚未被受理时成功。
     *
     * @return 受影响行数，0 表示已被并发计划抢走
     */
    public int markUsedIfFree(long contributionId, String planNo) {
        return jdbc.update(
                "UPDATE contribution SET used_by_plan_no = ? WHERE id = ? AND used_by_plan_no IS NULL",
                planNo, contributionId);
    }

    /**
     * 统计成员尚未受理的缴费笔数。
     */
    public int countUnusedByMember(String memberNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contribution WHERE member_no = ? AND used_by_plan_no IS NULL",
                Integer.class, memberNo);
        return count == null ? 0 : count;
    }

    private static ContributionPo map(ResultSet rs) throws SQLException {
        return new ContributionPo(
                rs.getLong("id"),
                rs.getString("member_no"),
                rs.getObject("contribution_date", LocalDate.class),
                ContributionSource.valueOf(rs.getString("source")),
                rs.getLong("amount_cents"),
                rs.getString("used_by_plan_no"),
                rs.getTimestamp("created_at_utc").toInstant());
    }
}
