package com.example.starter.ledger.repo;

import com.example.starter.ledger.po.ChainAlertPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 链不一致告警数据访问。
 */
@Repository
public class ChainAlertRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChainAlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ChainAlertPO> MAPPER = new AlertRowMapper();

    /** 落库一条链不一致告警。 */
    public void insert(ChainAlertPO alert) {
        jdbcTemplate.update(
                "INSERT INTO chain_alert (battery_code, station_code, reason, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                alert.getBatteryCode(), alert.getStationCode(), alert.getReason(), alert.getRequestId(),
                Timestamp.from(alert.getCreatedAt()));
    }

    /** 按电池编码查询告警，按时间稳定升序。 */
    public List<ChainAlertPO> findByBattery(String batteryCode) {
        return jdbcTemplate.query(
                "SELECT id, battery_code, station_code, reason, request_id, created_at FROM chain_alert "
                        + "WHERE battery_code = ? ORDER BY id ASC", MAPPER, batteryCode);
    }

    /** 测试清理后自增序列无需复位：每场景使用独立合成编码。 */
    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chain_alert", Long.class);
        return count == null ? 0L : count;
    }

    private static final class AlertRowMapper implements RowMapper<ChainAlertPO> {
        @Override
        public ChainAlertPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChainAlertPO po = new ChainAlertPO();
            po.setId(rs.getLong("id"));
            po.setBatteryCode(rs.getString("battery_code"));
            po.setStationCode(rs.getString("station_code"));
            po.setReason(rs.getString("reason"));
            po.setRequestId(rs.getString("request_id"));
            Timestamp created = rs.getTimestamp("created_at");
            Instant createdAt = created == null ? null : created.toInstant();
            po.setCreatedAt(createdAt);
            return po;
        }
    }
}
