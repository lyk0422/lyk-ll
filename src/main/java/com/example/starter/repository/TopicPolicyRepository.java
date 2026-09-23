package com.example.starter.repository;

import com.example.starter.enums.RetentionStrategy;
import com.example.starter.model.TopicPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Topic 策略仓储。
 */
@Repository
public class TopicPolicyRepository {

    private static final PolicyRowMapper ROW_MAPPER = new PolicyRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public TopicPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按主题查找策略，未配置返回 null。 */
    public TopicPolicy findByTopic(String topic) {
        List<TopicPolicy> list = jdbcTemplate.query(
                "SELECT id, topic, strategy, bound, created_at, updated_at "
                        + "FROM topic_policy WHERE topic = ?",
                ROW_MAPPER, topic);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新增或更新主题策略。 */
    public void upsert(TopicPolicy policy) {
        int updated = jdbcTemplate.update(
                "UPDATE topic_policy SET strategy = ?, bound = ?, updated_at = ? WHERE topic = ?",
                policy.getStrategy().name(), policy.getBound(), policy.getUpdatedAt(),
                policy.getTopic());
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO topic_policy (topic, strategy, bound, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    policy.getTopic(), policy.getStrategy().name(), policy.getBound(),
                    policy.getCreatedAt(), policy.getUpdatedAt());
        }
    }

    private static final class PolicyRowMapper implements RowMapper<TopicPolicy> {
        @Override
        public TopicPolicy mapRow(ResultSet rs, int rowNum) throws SQLException {
            TopicPolicy policy = new TopicPolicy();
            policy.setId(rs.getLong("id"));
            policy.setTopic(rs.getString("topic"));
            policy.setStrategy(RetentionStrategy.valueOf(rs.getString("strategy")));
            int bound = rs.getInt("bound");
            policy.setBound(rs.wasNull() ? null : bound);
            policy.setCreatedAt(rs.getLong("created_at"));
            policy.setUpdatedAt(rs.getLong("updated_at"));
            return policy;
        }
    }
}
