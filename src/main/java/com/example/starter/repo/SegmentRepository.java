package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.SegmentRow;

/**
 * 路段档案数据访问。
 */
@Repository
public class SegmentRepository {

    private static final RowMapper<SegmentRow> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static SegmentRow map(ResultSet rs) throws SQLException {
        return new SegmentRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    public long insert(String code, String name, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO road_segment(code, name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setObject(3, createdAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<SegmentRow> findByCode(String code) {
        return jdbc.query("SELECT * FROM road_segment WHERE code = ?", MAPPER, code)
                .stream().findFirst();
    }

    public Optional<SegmentRow> findById(long id) {
        return jdbc.query("SELECT * FROM road_segment WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<SegmentRow> findAll() {
        return jdbc.query("SELECT * FROM road_segment ORDER BY code", MAPPER);
    }

    /**
     * 高风险路段：存在一级风险树木的路段ID列表。
     */
    public List<Long> findHighRiskSegmentIds() {
        return jdbc.queryForList(
                "SELECT DISTINCT segment_id FROM tree WHERE risk_level = 1", Long.class);
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM road_segment", Long.class);
    }
}
