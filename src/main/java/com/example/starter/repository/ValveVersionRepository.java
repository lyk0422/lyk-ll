package com.example.starter.repository;

import com.example.starter.domain.ValveVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 阀门开度版本链数据访问。
 */
@Repository
public class ValveVersionRepository {

    private static final RowMapper<ValveVersion> MAPPER = (rs, n) ->
            new ValveVersion(rs.getLong("id"), rs.getLong("valve_id"), rs.getLong("task_id"),
                    rs.getInt("version_no"), rs.getBigDecimal("old_opening"),
                    rs.getBigDecimal("new_opening"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ValveVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加版本记录；必须与开度写入在同一事务内调用。
     */
    public void insert(long valveId, long taskId, int versionNo,
                       BigDecimal oldOpening, BigDecimal newOpening, Instant createdAt) {
        jdbc.update("INSERT INTO valve_opening_version"
                        + " (valve_id, task_id, version_no, old_opening, new_opening, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                valveId, taskId, versionNo, oldOpening, newOpening, Timestamp.from(createdAt));
    }

    /**
     * 查询阀门当前最大版本号，无版本时返回 0。
     */
    public int maxVersionNo(long valveId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version_no) FROM valve_opening_version WHERE valve_id = ?", Integer.class, valveId);
        return max == null ? 0 : max;
    }

    /**
     * 查询阀门完整版本链，按版本号升序。
     */
    public List<ValveVersion> findByValveId(long valveId) {
        return jdbc.query("SELECT id, valve_id, task_id, version_no, old_opening, new_opening, created_at"
                + " FROM valve_opening_version WHERE valve_id = ? ORDER BY version_no", MAPPER, valveId);
    }
}
