package com.example.starter.pension.repo;

import com.example.starter.pension.po.LateContributionEventRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.List;

/**
 * 迟到缴费事件数据访问：同一缴费只记一次事件（contribution_id 唯一索引兜底）。
 */
@Repository
public class LateContributionEventRepository {

    private static final RowMapper<LateContributionEventRow> ROW_MAPPER = (rs, rowNum) -> new LateContributionEventRow(
            rs.getLong("id"),
            rs.getLong("member_id"),
            rs.getLong("contribution_id"),
            rs.getLong("claim_id"),
            rs.getLong("amount_cents"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC));

    private final JdbcClient jdbcClient;

    public LateContributionEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 记录一条迟到缴费事件（继承支付完成后到账的缴费，提示人工复核）。
     *
     * @return 受影响行数
     */
    public int insert(long memberId, long contributionId, long claimId, long amountCents, String note) {
        return jdbcClient.sql("""
                        INSERT INTO late_contribution_event
                            (member_id, contribution_id, claim_id, amount_cents, note)
                        VALUES
                            (:memberId, :contributionId, :claimId, :amountCents, :note)""")
                .param("memberId", memberId)
                .param("contributionId", contributionId)
                .param("claimId", claimId)
                .param("amountCents", amountCents)
                .param("note", note)
                .update();
    }

    /** 查询成员的全部迟到缴费事件，按主键升序。 */
    public List<LateContributionEventRow> findByMember(long memberId) {
        return jdbcClient.sql("""
                        SELECT id, member_id, contribution_id, claim_id, amount_cents, note, created_at
                          FROM late_contribution_event
                         WHERE member_id = :memberId
                         ORDER BY id""")
                .param("memberId", memberId)
                .query(ROW_MAPPER)
                .list();
    }
}
