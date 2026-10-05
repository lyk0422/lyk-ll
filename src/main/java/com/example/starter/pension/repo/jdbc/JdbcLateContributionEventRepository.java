package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.repo.LateContributionEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 迟到缴费事件 JDBC 实现。同一缴费只记一次事件由 contribution_id 唯一索引保证。
 */
@Repository
public class JdbcLateContributionEventRepository implements LateContributionEventRepository {

    private final JdbcClient jdbcClient;

    public JdbcLateContributionEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public int insert(long memberId, long contributionId, long claimId) {
        return jdbcClient.sql("""
                        INSERT INTO late_contribution_event (member_id, contribution_id, claim_id)
                        VALUES (:memberId, :contributionId, :claimId)""")
                .param("memberId", memberId)
                .param("contributionId", contributionId)
                .param("claimId", claimId)
                .update();
    }

    @Override
    public long countPendingByMember(long memberId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM late_contribution_event
                         WHERE member_id = :memberId AND review_status = 'PENDING'""")
                .param("memberId", memberId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }
}
