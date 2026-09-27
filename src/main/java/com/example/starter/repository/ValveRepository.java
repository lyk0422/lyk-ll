package com.example.starter.repository;

import com.example.starter.domain.Valve;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 阀门数据访问：当前开度的读取与写入。
 */
@Repository
public class ValveRepository {

    private static final RowMapper<Valve> MAPPER = (rs, n) ->
            new Valve(rs.getLong("id"), rs.getLong("unit_id"),
                    rs.getBigDecimal("opening_percent"), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ValveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按单元 ID 查询阀门。
     */
    public Optional<Valve> findByUnitId(long unitId) {
        return jdbc.query("SELECT id, unit_id, opening_percent, updated_at FROM valve WHERE unit_id = ?",
                MAPPER, unitId).stream().findFirst();
    }

    /**
     * 按阀门 ID 查询。
     */
    public Optional<Valve> findById(long id) {
        return jdbc.query("SELECT id, unit_id, opening_percent, updated_at FROM valve WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按阀门 ID 查询并加行级写锁，用于串行化同一受控阀门的铅封、解封与调节竞争。
     */
    public Optional<Valve> lockById(long id) {
        return jdbc.query("SELECT id, unit_id, opening_percent, updated_at FROM valve WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 更新阀门当前开度；必须与版本记录在同一事务内调用。
     */
    public void updateOpening(long valveId, BigDecimal newOpening, Instant updatedAt) {
        jdbc.update("UPDATE valve SET opening_percent = ?, updated_at = ? WHERE id = ?",
                newOpening, Timestamp.from(updatedAt), valveId);
    }

    /**
     * 条件更新阀门开度：仅当当前开度版本（版本链最大版本号，无版本为 0）与期望一致时写入。
     * 用于复封与受控调节竞争下保证“已复封不可再追加调节版本”，返回更新行数。
     */
    public int updateOpeningIfVersion(long valveId, BigDecimal oldOpening, BigDecimal newOpening,
                                      int expectedVersion, Instant updatedAt) {
        return jdbc.update("UPDATE valve SET opening_percent = ?, updated_at = ?"
                        + " WHERE id = ? AND opening_percent = ?"
                        + " AND (SELECT COALESCE(MAX(version_no), 0)"
                        + " FROM valve_opening_version WHERE valve_id = ?) = ?",
                newOpening, Timestamp.from(updatedAt), valveId, oldOpening, valveId, expectedVersion);
    }
}
