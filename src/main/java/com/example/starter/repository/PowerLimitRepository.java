package com.example.starter.repository;

import com.example.starter.model.PowerLimitRow;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 限功率链数据访问：active_blade_id 唯一索引保证同叶片仅一条生效中限功率。
 */
@Repository
public class PowerLimitRepository {

    private static final RowMapper<PowerLimitRow> ROW_MAPPER = (rs, n) -> new PowerLimitRow(
            rs.getLong("id"),
            rs.getLong("blade_id"),
            rs.getString("reason"),
            rs.getObject("started_at", LocalDateTime.class),
            rs.getObject("revoked_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public PowerLimitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询叶片当前生效中的限功率。
     */
    public Optional<PowerLimitRow> findActiveByBlade(long bladeId) {
        List<PowerLimitRow> rows = jdbc.query(
                "SELECT id, blade_id, reason, started_at, revoked_at FROM power_limit"
                        + " WHERE active_blade_id = ?",
                ROW_MAPPER, bladeId);
        return rows.stream().findFirst();
    }

    /**
     * 开始限功率，返回自增主键。
     */
    public long insert(long bladeId, String reason, LocalDateTime startedAt, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO power_limit(blade_id, reason, started_at, active_blade_id, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bladeId);
            ps.setString(2, reason);
            ps.setTimestamp(3, Timestamp.valueOf(startedAt));
            ps.setLong(4, bladeId);
            ps.setTimestamp(5, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 撤销限功率：置撤销时刻并释放生效占用。
     */
    public void revoke(long id, LocalDateTime revokedAt) {
        jdbc.update(
                "UPDATE power_limit SET revoked_at = ?, active_blade_id = NULL WHERE id = ?",
                Timestamp.valueOf(revokedAt), id);
    }

    /**
     * 限功率链：按叶片列出全部限功率区间，顺序稳定。
     */
    public List<PowerLimitRow> listByBlade(long bladeId) {
        return jdbc.query(
                "SELECT id, blade_id, reason, started_at, revoked_at FROM power_limit"
                        + " WHERE blade_id = ? ORDER BY started_at, id",
                ROW_MAPPER, bladeId);
    }
}
