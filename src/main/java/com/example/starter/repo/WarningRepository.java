package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.WarningRow;

/**
 * 大风预警数据访问。
 */
@Repository
public class WarningRepository {

    private static final RowMapper<WarningRow> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public WarningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static WarningRow map(ResultSet rs) throws SQLException {
        return new WarningRow(rs.getLong("id"), rs.getString("warning_no"), rs.getInt("level"),
                rs.getString("status"), rs.getObject("published_at", LocalDateTime.class),
                rs.getObject("cancelled_at", LocalDateTime.class));
    }

    public long insert(String warningNo, int level, LocalDateTime publishedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO gale_warning(warning_no, level, status, published_at)"
                            + " VALUES (?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, warningNo);
            ps.setInt(2, level);
            ps.setObject(3, publishedAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<WarningRow> findByNo(String warningNo) {
        return jdbc.query("SELECT * FROM gale_warning WHERE warning_no = ?", MAPPER, warningNo)
                .stream().findFirst();
    }

    public void cancel(long id, LocalDateTime cancelledAt) {
        jdbc.update("UPDATE gale_warning SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                cancelledAt, id);
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gale_warning", Long.class);
    }
}
