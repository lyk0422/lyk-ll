package com.example.starter.repo;

import com.example.starter.domain.LineStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 索道线路数据访问。
 */
@Repository
public class LineRepository {

    /** 线路行记录。 */
    public record LineRow(long id, String code, String name, LineStatus status) {
    }

    private final JdbcTemplate jdbc;

    public LineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LineRow> findById(long id) {
        return jdbc.query("SELECT id, code, name, status FROM ski_line WHERE id = ?",
                (rs, n) -> map(rs), id).stream().findFirst();
    }

    /**
     * 按 id 加行锁读取（SELECT ... FOR UPDATE），用于串行化同线路的管制判定。
     */
    public Optional<LineRow> lockById(long id) {
        return jdbc.query("SELECT id, code, name, status FROM ski_line WHERE id = ? FOR UPDATE",
                (rs, n) -> map(rs), id).stream().findFirst();
    }

    public void updateStatus(long id, LineStatus status) {
        jdbc.update("UPDATE ski_line SET status = ? WHERE id = ?", status.name(), id);
    }

    private LineRow map(ResultSet rs) throws SQLException {
        return new LineRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                LineStatus.valueOf(rs.getString("status")));
    }
}
