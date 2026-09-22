package com.example.starter.rosbag.repository;

import com.example.starter.rosbag.model.ReplayRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 回放记录数据访问；replay_key 上有唯一约束保证重复请求不重复建记录。
 */
@Repository
public class ReplayRepository {

    private static final RowMapper<ReplayRecord> MAPPER = (rs, n) -> new ReplayRecord(
            rs.getLong("id"),
            rs.getString("replay_key"),
            rs.getString("topic"),
            rs.getObject("from_ns", Long.class),
            rs.getObject("to_ns", Long.class),
            rs.getLong("message_count"),
            rs.getLong("created_at")
    );

    private final JdbcTemplate jdbc;

    public ReplayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReplayRecord findByKey(String replayKey) {
        List<ReplayRecord> list = jdbc.query(
                "SELECT id, replay_key, topic, from_ns, to_ns, message_count, created_at "
                        + "FROM rb_replay WHERE replay_key = ?",
                MAPPER, replayKey);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入回放记录；唯一约束冲突时抛 {@link DuplicateKeyException}，由上层按幂等处理。
     */
    public ReplayRecord insert(String replayKey, String topic, Long fromNs, Long toNs,
                               long messageCount, long createdAtNs) {
        jdbc.update(
                "INSERT INTO rb_replay (replay_key, topic, from_ns, to_ns, message_count, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                replayKey, topic, fromNs, toNs, messageCount, createdAtNs);
        return findByKey(replayKey);
    }
}
