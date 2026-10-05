package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 恢复投料记录访问：投料请求与升温曲线状态在同一事务校验后落库。
 */
@Repository
public class FeedRecordRepository {

    private final JdbcTemplate jdbc;

    public FeedRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入投料记录并回填自增主键。 */
    public long insert(String requestId, String kilnId, long ticketId, LocalDateTime fedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO feed_record (request_id, kiln_id, ticket_id, fed_at)
                    VALUES (?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, requestId);
            ps.setString(2, kilnId);
            ps.setLong(3, ticketId);
            ps.setTimestamp(4, Timestamp.valueOf(fedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }
}
