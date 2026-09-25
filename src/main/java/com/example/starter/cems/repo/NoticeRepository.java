package com.example.starter.cems.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.cems.web.dto.NoticeView;

/**
 * 数据缺失督办仓储。
 */
@Repository
public class NoticeRepository {

    private final JdbcTemplate jdbc;

    public NoticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 生成督办（连续缺失严格超过 2 小时才调用）。返回自增主键。 */
    public long insert(NoticeView v) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO missing_notice(outfall_code, gap_start, gap_end, missing_hours, "
                            + "status, created_at) VALUES (?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, v.outfallCode());
            ps.setTimestamp(2, Timestamp.valueOf(v.gapStart()));
            ps.setTimestamp(3, Timestamp.valueOf(v.gapEnd()));
            ps.setInt(4, v.missingHours());
            ps.setString(5, v.status());
            ps.setTimestamp(6, Timestamp.valueOf(v.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 某排口是否已存在覆盖该缺失区间的督办，用于去重。 */
    public NoticeView findGap(String outfallCode, LocalDateTime gapStart, LocalDateTime gapEnd) {
        var list = jdbc.query(
                "SELECT * FROM missing_notice WHERE outfall_code = ? AND gap_start = ? AND gap_end = ?",
                MAPPER, outfallCode, Timestamp.valueOf(gapStart), Timestamp.valueOf(gapEnd));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 列出某排口全部督办（可复算）。 */
    public List<NoticeView> findAllByCode(String outfallCode) {
        return jdbc.query(
                "SELECT * FROM missing_notice WHERE outfall_code = ? ORDER BY gap_start, id",
                MAPPER, outfallCode);
    }

    /** 复算前清理某排口督办。 */
    public void deleteByCode(String outfallCode) {
        jdbc.update("DELETE FROM missing_notice WHERE outfall_code = ?", outfallCode);
    }

    /** 测试清理。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM missing_notice");
    }

    private static final RowMapper<NoticeView> MAPPER = new RowMapper<>() {
        @Override
        public NoticeView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new NoticeView(
                    rs.getLong("id"),
                    rs.getString("outfall_code"),
                    rs.getTimestamp("gap_start").toLocalDateTime(),
                    rs.getTimestamp("gap_end").toLocalDateTime(),
                    rs.getInt("missing_hours"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    };
}
