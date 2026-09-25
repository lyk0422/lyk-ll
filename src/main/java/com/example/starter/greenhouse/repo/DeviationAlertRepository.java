package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.domain.AlertStatus;
import com.example.starter.greenhouse.po.DeviationAlert;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 偏差告警数据访问。
 */
@Repository
public class DeviationAlertRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public DeviationAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withTableName("deviation_alert").usingGeneratedKeyColumns("id");
    }

    public DeviationAlert insert(DeviationAlert a) {
        var p = new HashMap<String, Object>();
        p.put("zone_id", a.getZoneId());
        p.put("strategy_id", a.getStrategyId());
        p.put("status", a.getStatus().name());
        p.put("first_deviate_time", Timestamp.from(a.getFirstDeviateTime()));
        p.put("trigger_sample_time", Timestamp.from(a.getTriggerSampleTime()));
        p.put("recovered_at", a.getRecoveredAt() == null ? null : Timestamp.from(a.getRecoveredAt()));
        p.put("acknowledged_at", a.getAcknowledgedAt() == null ? null : Timestamp.from(a.getAcknowledgedAt()));
        p.put("ack_request_id", a.getAckRequestId());
        p.put("created_at", Timestamp.from(a.getCreatedAt()));
        Number key = insert.executeAndReturnKey(p);
        a.setId(key.longValue());
        return a;
    }

    public Optional<DeviationAlert> findById(Long id) {
        return jdbc.query("SELECT * FROM deviation_alert WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 取分区当前未恢复的最近一条 OPEN 告警（用于避免同一次连续偏离重复触发）。 */
    public Optional<DeviationAlert> findOpenByZone(Long zoneId) {
        return jdbc.query("SELECT * FROM deviation_alert WHERE zone_id = ? AND status = 'OPEN' "
                        + "AND recovered_at IS NULL ORDER BY id DESC LIMIT 1",
                MAPPER, zoneId).stream().findFirst();
    }

    /** 按触发采样时刻查告警，用于乱序归位补算时的幂等去重。 */
    public Optional<DeviationAlert> findByZoneAndTriggerTime(Long zoneId, Instant triggerSampleTime) {
        return jdbc.query("SELECT * FROM deviation_alert WHERE zone_id = ? AND trigger_sample_time = ?",
                MAPPER, zoneId, Timestamp.from(triggerSampleTime)).stream().findFirst();
    }

    public void acknowledge(Long id, String requestId, Instant at) {
        jdbc.update("UPDATE deviation_alert SET status = 'ACKNOWLEDGED', acknowledged_at = ?, ack_request_id = ? "
                + "WHERE id = ?", Timestamp.from(at), requestId, id);
    }

    /** 读数回到设定带内时关闭未恢复告警（不影响确认状态记录）。 */
    public void markRecovered(Long id, Instant at) {
        jdbc.update("UPDATE deviation_alert SET recovered_at = ? WHERE id = ? AND recovered_at IS NULL",
                Timestamp.from(at), id);
    }

    static final RowMapper<DeviationAlert> MAPPER = (ResultSet rs, int rowNum) -> {
        DeviationAlert a = new DeviationAlert();
        a.setId(rs.getLong("id"));
        a.setZoneId(rs.getLong("zone_id"));
        a.setStrategyId(rs.getLong("strategy_id"));
        a.setStatus(AlertStatus.valueOf(rs.getString("status")));
        a.setFirstDeviateTime(rs.getTimestamp("first_deviate_time").toInstant());
        a.setTriggerSampleTime(rs.getTimestamp("trigger_sample_time").toInstant());
        Timestamp recovered = rs.getTimestamp("recovered_at");
        a.setRecoveredAt(recovered == null ? null : recovered.toInstant());
        Timestamp acked = rs.getTimestamp("acknowledged_at");
        a.setAcknowledgedAt(acked == null ? null : acked.toInstant());
        a.setAckRequestId(rs.getString("ack_request_id"));
        a.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return a;
    };
}
