package com.example.starter.cems.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.cems.web.dto.ReadingView;

/**
 * 监测读数仓储，按“排口＋时段”唯一去重。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入读数；重复（排口＋时段）由唯一索引抛出 DuplicateKeyException。 */
    public void insert(String outfallCode, String periodType, LocalDateTime periodStart,
                       BigDecimal avgValue, String requestId, LocalDateTime createdAt, boolean late) {
        jdbc.update(
                "INSERT INTO monitor_reading(outfall_code, period_type, period_start, avg_value, "
                        + "is_late, request_id, created_at) VALUES (?,?,?,?,?,?,?)",
                outfallCode, periodType, Timestamp.valueOf(periodStart), avgValue,
                late ? 1 : 0, requestId, Timestamp.valueOf(createdAt));
    }

    /** 查询某排口某时段读数。 */
    public ReadingView findSlot(String outfallCode, String periodType, LocalDateTime periodStart) {
        var list = jdbc.query(
                "SELECT * FROM monitor_reading WHERE outfall_code = ? AND period_type = ? AND period_start = ?",
                MAPPER, outfallCode, periodType, Timestamp.valueOf(periodStart));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按时间正序返回区间内小时读数（含两端）。 */
    public List<ReadingView> findHourlyBetween(String outfallCode, LocalDateTime from, LocalDateTime to) {
        return jdbc.query(
                "SELECT * FROM monitor_reading WHERE outfall_code = ? AND period_type = 'HOUR' "
                        + "AND period_start BETWEEN ? AND ? ORDER BY period_start",
                MAPPER, outfallCode, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    /** 返回某排口截至指定时刻（含）的全部小时读数，按时间正序。 */
    public List<ReadingView> findHourlyUpTo(String outfallCode, LocalDateTime to) {
        return jdbc.query(
                "SELECT * FROM monitor_reading WHERE outfall_code = ? AND period_type = 'HOUR' "
                        + "AND period_start <= ? ORDER BY period_start",
                MAPPER, outfallCode, Timestamp.valueOf(to));
    }

    /** 返回某排口全部小时读数，按时间正序。 */
    public List<ReadingView> findAllHourly(String outfallCode) {
        return jdbc.query(
                "SELECT * FROM monitor_reading WHERE outfall_code = ? AND period_type = 'HOUR' "
                        + "ORDER BY period_start",
                MAPPER, outfallCode);
    }

    /** 返回某排口全部日读数，按时间正序。 */
    public List<ReadingView> findAllDaily(String outfallCode) {
        return jdbc.query(
                "SELECT * FROM monitor_reading WHERE outfall_code = ? AND period_type = 'DAY' "
                        + "ORDER BY period_start",
                MAPPER, outfallCode);
    }

    /** 截至指定时刻（含）小时均值累计之和，作为排放总量的合成代理基数。 */
    public BigDecimal sumHourlyUpTo(String outfallCode, LocalDateTime to) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(avg_value), 0) FROM monitor_reading "
                        + "WHERE outfall_code = ? AND period_type = 'HOUR' AND period_start <= ?",
                BigDecimal.class, outfallCode, Timestamp.valueOf(to));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 测试清理。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM monitor_reading");
    }

    private static final RowMapper<ReadingView> MAPPER = new RowMapper<>() {
        @Override
        public ReadingView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReadingView(
                    rs.getLong("id"),
                    rs.getString("outfall_code"),
                    rs.getString("period_type"),
                    rs.getTimestamp("period_start").toLocalDateTime(),
                    rs.getBigDecimal("avg_value"),
                    rs.getInt("is_late") == 1,
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    };
}
