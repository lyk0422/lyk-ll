package com.example.starter.repo.jdbc;

import com.example.starter.domain.RetentionPolicy;
import com.example.starter.po.TopicConfig;
import com.example.starter.repo.TopicConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 基于 {@link JdbcTemplate} 的 Topic 配置访问，支持 H2（MODE=MySQL）与 MySQL。 */
@Repository
public class JdbcTopicConfigRepository implements TopicConfigRepository {

    private static final RowMapper<TopicConfig> ROW_MAPPER = (rs, rowNum) -> new TopicConfig(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getString("policy_type"),
            (Integer) rs.getObject("capacity"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public JdbcTopicConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TopicConfig upsert(String topic, RetentionPolicy policy) {
        Instant now = Instant.now();
        Integer capacity = policy.type().name().equals("BOUNDED") ? policy.capacity() : null;
        TopicConfig existing = findByTopic(topic).orElse(null);
        if (existing == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO topic_config (topic, policy_type, capacity, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, topic);
                ps.setString(2, policy.type().name());
                ps.setObject(3, capacity);
                ps.setTimestamp(4, Timestamp.from(now));
                ps.setTimestamp(5, Timestamp.from(now));
                return ps;
            }, keyHolder);
            long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
            return new TopicConfig(id, topic, policy.type().name(), capacity, now, now);
        }
        jdbcTemplate.update(
                "UPDATE topic_config SET policy_type = ?, capacity = ?, updated_at = ? WHERE id = ?",
                policy.type().name(), capacity, Timestamp.from(now), existing.id());
        return new TopicConfig(existing.id(), topic, policy.type().name(), capacity,
                existing.createdAt(), now);
    }

    @Override
    public Optional<TopicConfig> findByTopic(String topic) {
        List<TopicConfig> list = jdbcTemplate.query(
                "SELECT id, topic, policy_type, capacity, created_at, updated_at "
                        + "FROM topic_config WHERE topic = ?",
                ROW_MAPPER, topic);
        return list.stream().findFirst();
    }

    @Override
    public List<TopicConfig> findAll() {
        return jdbcTemplate.query(
                "SELECT id, topic, policy_type, capacity, created_at, updated_at "
                        + "FROM topic_config ORDER BY topic",
                ROW_MAPPER);
    }
}
