package com.example.starter.repo;

import com.example.starter.model.LineStatus;
import com.example.starter.model.WindLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 线路数据访问。
 */
@Repository
public class LineRepository {

    private static final RowMapper<WindLine> MAPPER = (rs, n) -> new WindLine(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            LineStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public LineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 id 查询线路。
     */
    public Optional<WindLine> findById(long id) {
        return jdbc.query("SELECT id, code, name, status FROM wind_line WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 id 查询并加行锁（事务内串行化线路级写操作）。
     */
    public Optional<WindLine> findByIdForUpdate(long id) {
        return jdbc.query("SELECT id, code, name, status FROM wind_line WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 更新线路状态。
     */
    public void updateStatus(long id, LineStatus status) {
        jdbc.update("UPDATE wind_line SET status = ? WHERE id = ?", status.name(), id);
    }
}
