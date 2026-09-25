package com.example.starter.cems.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.cems.web.dto.OutfallView;

/**
 * 排口仓储。
 */
@Repository
public class OutfallRepository {

    private static final String COLUMNS =
            "outfall_code, outfall_name, status, daily_limit, hourly_limit, "
                    + "total_baseline, baseline_frozen_at, registered_at, updated_at";

    private final JdbcTemplate jdbc;

    public OutfallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记排口；重复排口编码由唯一索引抛出 DuplicateKeyException。 */
    public void insert(OutfallView v) {
        jdbc.update(
                "INSERT INTO outfall(outfall_code, outfall_name, status, daily_limit, hourly_limit, "
                        + "total_baseline, baseline_frozen_at, registered_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                v.outfallCode(), v.outfallName(), v.status(), v.dailyLimit(), v.hourlyLimit(),
                v.totalBaseline(), ts(v.baselineFrozenAt()), ts(v.registeredAt()), ts(v.updatedAt()));
    }

    /** 按编码查询排口，不存在返回 null。 */
    public OutfallView findByCode(String code) {
        var list = jdbc.query(
                "SELECT " + COLUMNS + " FROM outfall WHERE outfall_code = ?",
                MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 仅更新排口状态。 */
    public void updateStatus(String code, String status, LocalDateTime updatedAt) {
        jdbc.update("UPDATE outfall SET status = ?, updated_at = ? WHERE outfall_code = ?",
                status, ts(updatedAt), code);
    }

    /**
     * 限排生效同事务冻结排放总量基数：写入基数、冻结时刻并置为限排中。
     * 与限排令插入在同一事务内调用，任一失败整体回滚。
     */
    public void freezeBaseline(String code, BigDecimal baseline,
                               LocalDateTime frozenAt, LocalDateTime updatedAt) {
        int rows = jdbc.update(
                "UPDATE outfall SET total_baseline = ?, baseline_frozen_at = ?, "
                        + "status = 'RESTRICTED', updated_at = ? WHERE outfall_code = ?",
                baseline, ts(frozenAt), ts(updatedAt), code);
        if (rows != 1) {
            throw new IllegalStateException("冻结排放总量基数失败，排口不存在: " + code);
        }
    }

    /** 复测解除：回到监测中（保留冻结基数作为历史记录）。 */
    public void backToMonitoring(String code, LocalDateTime updatedAt) {
        jdbc.update("UPDATE outfall SET status = 'MONITORING', updated_at = ? WHERE outfall_code = ?",
                ts(updatedAt), code);
    }

    /** 归档终态。 */
    public void markArchived(String code, LocalDateTime updatedAt) {
        int rows = jdbc.update(
                "UPDATE outfall SET status = 'ARCHIVED', updated_at = ? WHERE outfall_code = ?",
                ts(updatedAt), code);
        if (rows != 1) {
            throw new IllegalStateException("归档失败，排口不存在: " + code);
        }
    }

    /** 测试清理。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM outfall");
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static final RowMapper<OutfallView> MAPPER = new RowMapper<>() {
        @Override
        public OutfallView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new OutfallView(
                    rs.getString("outfall_code"),
                    rs.getString("outfall_name"),
                    rs.getString("status"),
                    rs.getBigDecimal("daily_limit"),
                    rs.getBigDecimal("hourly_limit"),
                    rs.getBigDecimal("total_baseline"),
                    rs.getTimestamp("baseline_frozen_at") == null ? null
                            : rs.getTimestamp("baseline_frozen_at").toLocalDateTime(),
                    rs.getTimestamp("registered_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime(),
                    "ARCHIVED".equals(rs.getString("status")));
        }
    };
}
