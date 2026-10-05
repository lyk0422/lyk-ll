package com.example.starter.flood.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 入库洪水过程线仓储：同一处置链内按预报时刻唯一，重复上报同时刻点按新值更新（乱序归位）。
 */
@Repository
public class HydrographRepository {

    private final JdbcTemplate jdbcTemplate;

    public HydrographRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<HydrographPointPO> MAPPER = new RowMapper<>() {
        @Override
        public HydrographPointPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            HydrographPointPO po = new HydrographPointPO();
            po.id = rs.getLong("id");
            po.warningId = rs.getLong("warning_id");
            po.forecastTime = rs.getTimestamp("forecast_time").toLocalDateTime();
            po.inflowRate = rs.getBigDecimal("inflow_rate");
            po.requestId = rs.getString("request_id");
            Timestamp created = rs.getTimestamp("created_at");
            po.createdAt = created == null ? null : created.toLocalDateTime();
            return po;
        }
    };

    /**
     * 写入过程线点；同时刻已存在则更新流量（后报覆盖先报，按时刻归位）。
     */
    public void upsertPoint(Long warningId, LocalDateTime forecastTime,
                            java.math.BigDecimal inflowRate, String requestId,
                            LocalDateTime createdAt) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO inflow_hydrograph_point(warning_id, forecast_time, inflow_rate, "
                            + "request_id, created_at) VALUES (?,?,?,?,?)",
                    warningId, Timestamp.valueOf(forecastTime), inflowRate, requestId,
                    Timestamp.valueOf(createdAt));
        } catch (DuplicateKeyException e) {
            jdbcTemplate.update(
                    "UPDATE inflow_hydrograph_point SET inflow_rate = ? "
                            + "WHERE warning_id = ? AND forecast_time = ?",
                    inflowRate, warningId, Timestamp.valueOf(forecastTime));
        }
    }

    /**
     * 读取处置链全部过程线点，按预报时刻升序。
     */
    public List<HydrographPointPO> findByWarningId(Long warningId) {
        return jdbcTemplate.query(
                "SELECT * FROM inflow_hydrograph_point WHERE warning_id = ? "
                        + "ORDER BY forecast_time",
                MAPPER, warningId);
    }

    public int countByWarningId(Long warningId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM inflow_hydrograph_point WHERE warning_id = ?",
                Integer.class, warningId);
        return count == null ? 0 : count;
    }
}
