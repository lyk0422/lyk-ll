package com.example.starter.cems.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.cems.web.dto.MarkView;

/**
 * 超标标记仓储（按排口＋自然日唯一）。
 */
@Repository
public class MarkRepository {

    private final JdbcTemplate jdbc;

    public MarkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入超标标记；重复 requestId 或重复排口＋自然日由唯一索引拒绝。返回自增主键。 */
    public long insert(MarkView v) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO exceedance_mark(outfall_code, request_id, day_start, daily_avg, "
                            + "limit_value, status, created_at) VALUES (?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, v.outfallCode());
            ps.setString(2, v.requestId());
            ps.setTimestamp(3, Timestamp.valueOf(v.dayStart()));
            ps.setBigDecimal(4, v.dailyAvg());
            ps.setBigDecimal(5, v.limitValue());
            ps.setString(6, v.status());
            ps.setTimestamp(7, Timestamp.valueOf(v.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 查询某排口某日标记。 */
    public MarkView findByDay(String outfallCode, LocalDateTime dayStart) {
        var list = jdbc.query(
                "SELECT * FROM exceedance_mark WHERE outfall_code = ? AND day_start = ?",
                MAPPER, outfallCode, Timestamp.valueOf(dayStart));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 列出某排口全部标记（标记链，按时间正序）。 */
    public List<MarkView> findAllByCode(String outfallCode) {
        return jdbc.query(
                "SELECT * FROM exceedance_mark WHERE outfall_code = ? ORDER BY day_start, id",
                MAPPER, outfallCode);
    }

    /** 将某排口全部有效标记置为复测解除。 */
    public int resolveActive(String outfallCode) {
        return jdbc.update(
                "UPDATE exceedance_mark SET status = 'RESOLVED' "
                        + "WHERE outfall_code = ? AND status = 'ACTIVE'",
                outfallCode);
    }

    /** 测试清理。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM exceedance_mark");
    }

    private static final RowMapper<MarkView> MAPPER = new RowMapper<>() {
        @Override
        public MarkView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MarkView(
                    rs.getLong("id"),
                    rs.getString("outfall_code"),
                    rs.getString("request_id"),
                    rs.getTimestamp("day_start").toLocalDateTime(),
                    rs.getBigDecimal("daily_avg"),
                    rs.getBigDecimal("limit_value"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    };
}
