package com.example.starter.segment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 路段档案数据访问。
 */
@Repository
public class SegmentRepository {

    private final JdbcClient jdbc;

    public SegmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 路段行记录。 */
    public record SegmentRow(long id, String name, LocalDateTime createdAt) {
    }

    public long insert(String name, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO road_segment(name, created_at) VALUES (?, ?)")
                .params(name, createdAt)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<SegmentRow> findById(long id) {
        return jdbc.sql("SELECT id, name, created_at FROM road_segment WHERE id = ?")
                .param(id)
                .query((rs, i) -> new SegmentRow(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .optional();
    }

    public boolean existsByName(String name) {
        return jdbc.sql("SELECT COUNT(*) FROM road_segment WHERE name = ?")
                .param(name)
                .query(Long.class)
                .single() > 0;
    }

    public List<SegmentRow> listAll() {
        return jdbc.sql("SELECT id, name, created_at FROM road_segment ORDER BY id")
                .query((rs, i) -> new SegmentRow(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }
}
