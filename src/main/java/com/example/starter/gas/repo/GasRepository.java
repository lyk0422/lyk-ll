package com.example.starter.gas.repo;

import com.example.starter.gas.model.EventKind;
import com.example.starter.gas.model.LinkageTaskStatus;
import com.example.starter.gas.model.LinkageTaskType;
import com.example.starter.gas.model.PressureAction;
import com.example.starter.gas.model.ValveState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 门站业务数据访问层（JdbcTemplate + 参数化 SQL）。
 */
@Repository
public class GasRepository {

    private final JdbcTemplate jdbc;

    public GasRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 门站静态参数与实时状态行。 */
    public record StationRow(
            long id,
            String stationCode,
            String stationName,
            BigDecimal odorTarget,
            BigDecimal odorTolerance,
            BigDecimal pressureLow,
            BigDecimal pressureHigh,
            BigDecimal odorConsumed,
            ValveState valveState,
            boolean alarmActive,
            int violationCount
    ) {
    }

    private static final RowMapper<StationRow> STATION_MAPPER = new RowMapper<>() {
        @Override
        public StationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new StationRow(
                    rs.getLong("id"),
                    rs.getString("station_code"),
                    rs.getString("station_name"),
                    rs.getBigDecimal("odor_target"),
                    rs.getBigDecimal("odor_tolerance"),
                    rs.getBigDecimal("pressure_low"),
                    rs.getBigDecimal("pressure_high"),
                    rs.getBigDecimal("odor_consumed"),
                    ValveState.valueOf(rs.getString("valve_state")),
                    rs.getBoolean("alarm_active"),
                    rs.getInt("violation_count")
            );
        }
    };

    /** 查询门站；forUpdate=true 时加行锁（SELECT ... FOR UPDATE），用于检测/联动事务内串行化。 */
    public StationRow findStation(String stationCode, boolean forUpdate) {
        String sql = "SELECT id, station_code, station_name, odor_target, odor_tolerance, pressure_low, "
                + "pressure_high, odor_consumed, valve_state, alarm_active, violation_count "
                + "FROM gate_station WHERE station_code = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        List<StationRow> rows = jdbc.query(sql, STATION_MAPPER, stationCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 在行锁保护下更新门站实时状态：阀门、告警、连续越限计数、累计实耗。 */
    public void updateStationRuntime(String stationCode, ValveState valveState, boolean alarmActive,
                                     int violationCount, BigDecimal odorConsumed) {
        jdbc.update("UPDATE gate_station SET valve_state = ?, alarm_active = ?, violation_count = ?, "
                        + "odor_consumed = ?, updated_at = CURRENT_TIMESTAMP WHERE station_code = ?",
                valveState.name(), alarmActive, violationCount, odorConsumed, stationCode);
    }

    /** 幂等登记行。 */
    public record IdempotentRow(String requestId, String requestType, String fingerprint, String responseBody) {
    }

    private static final RowMapper<IdempotentRow> IDEMPOTENT_MAPPER = (rs, n) -> new IdempotentRow(
            rs.getString("request_id"),
            rs.getString("request_type"),
            rs.getString("fingerprint"),
            rs.getString("response_body")
    );

    public IdempotentRow findIdempotent(String requestId) {
        List<IdempotentRow> rows = jdbc.query(
                "SELECT request_id, request_type, fingerprint, response_body "
                        + "FROM idempotent_request WHERE request_id = ?",
                IDEMPOTENT_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 幂等占位：业务开始前在独立事务登记，response_body 为空表示首发仍在执行；
     * 唯一约束使并发同键在触碰业务数据前被串行化。
     */
    public void insertPendingIdempotent(String requestId, String requestType, String fingerprint) {
        jdbc.update("INSERT INTO idempotent_request (request_id, request_type, fingerprint, response_body, "
                        + "idempotent_hit, created_at) VALUES (?, ?, ?, NULL, FALSE, CURRENT_TIMESTAMP)",
                requestId, requestType, fingerprint);
    }

    /** 首发事务成功后写回首次响应快照（同事务更新，随业务一起提交或回滚）。 */
    public void completeIdempotent(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotent_request SET response_body = ?, idempotent_hit = TRUE "
                        + "WHERE request_id = ?", responseBody, requestId);
    }

    /** 首发事务回滚后清理占位，使同键请求可重新受理。 */
    public void deleteIdempotent(String requestId) {
        jdbc.update("DELETE FROM idempotent_request WHERE request_id = ?", requestId);
    }

    public void insertDetection(String stationCode, String requestId, LocalDateTime detectedAt,
                                BigDecimal concentration, boolean inRange, int violationSeq) {
        jdbc.update("INSERT INTO concentration_detection (station_code, request_id, detected_at, "
                        + "concentration_mg, in_range, violation_seq, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, requestId, Timestamp.valueOf(detectedAt), concentration, inRange, violationSeq);
    }

    public void insertFlowReading(String stationCode, String requestId, LocalDateTime detectedAt, BigDecimal flowM3) {
        jdbc.update("INSERT INTO flow_reading (station_code, request_id, detected_at, flow_m3, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, requestId, Timestamp.valueOf(detectedAt), flowM3);
    }

    public void insertInjection(String stationCode, String sourceRequestId, LocalDateTime occurredAt,
                                BigDecimal flowM3, BigDecimal doseG,
                                BigDecimal theoreticalG, BigDecimal actualG, boolean valveLocked) {
        jdbc.update("INSERT INTO odorant_injection (station_code, source_request_id, occurred_at, flow_m3, "
                        + "dose_g, theoretical_consume_g, actual_consume_g, valve_locked, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, sourceRequestId, Timestamp.valueOf(occurredAt), flowM3, doseG,
                theoreticalG, actualG, valveLocked);
    }

    public void insertValveCommand(String stationCode, String requestId, String action,
                                   String operator1, String operator2, boolean effective) {
        jdbc.update("INSERT INTO valve_command (station_code, request_id, action, operator1, operator2, "
                        + "effective, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, requestId, action, operator1, operator2, effective);
    }

    public void insertPressureDetection(String stationCode, String requestId, LocalDateTime detectedAt,
                                        BigDecimal pressureKpa, PressureAction action) {
        jdbc.update("INSERT INTO pressure_detection (station_code, request_id, detected_at, pressure_kpa, "
                        + "pressure_action, created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, requestId, Timestamp.valueOf(detectedAt), pressureKpa, action.name());
    }

    public long insertLinkageTask(String stationCode, LinkageTaskType type, LinkageTaskStatus status,
                                  LocalDateTime startAt, LocalDateTime finishAt, String requestId) {
        var insert = new org.springframework.jdbc.core.simple.SimpleJdbcInsert(jdbc)
                .withTableName("linkage_task")
                .usingGeneratedKeyColumns("id");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("station_code", stationCode);
        params.put("task_type", type.name());
        params.put("status", status.name());
        params.put("start_at", Timestamp.valueOf(startAt));
        params.put("finish_at", finishAt == null ? null : Timestamp.valueOf(finishAt));
        params.put("request_id", requestId);
        params.put("created_at", new Timestamp(System.currentTimeMillis()));
        Number key = insert.executeAndReturnKey(params);
        return key.longValue();
    }

    /**
     * 受理同站同时刻联动槽位（永久保留受理事实）。并发同刻下达时唯一约束只放行一个，
     * 落选方抛出 {@link DuplicateKeyException}，由外层事务整体回滚。
     */
    public void occupyLinkageSlot(String stationCode, LocalDateTime startAt, String requestId, long taskId) {
        jdbc.update("INSERT INTO linkage_slot (station_code, start_at, request_id, task_id, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, Timestamp.valueOf(startAt), requestId, taskId);
    }

    public boolean existsLinkageSlot(String stationCode, LocalDateTime startAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM linkage_slot WHERE station_code = ? AND start_at = ?",
                Integer.class, stationCode, Timestamp.valueOf(startAt));
        return count != null && count > 0;
    }

    /** 日结单行。 */
    public record SettlementRow(
            String stationCode,
            LocalDate settleDate,
            String requestId,
            BigDecimal totalFlow,
            BigDecimal theoreticalConsume,
            BigDecimal actualConsume,
            BigDecimal deviationRatio,
            String status,
            LocalDateTime sealedAt
    ) {
    }

    public SettlementRow findSettlement(String stationCode, LocalDate settleDate) {
        List<SettlementRow> rows = jdbc.query(
                "SELECT station_code, settle_date, request_id, total_flow_m3, theoretical_consume_g, "
                        + "actual_consume_g, deviation_ratio, status, sealed_at "
                        + "FROM daily_settlement WHERE station_code = ? AND settle_date = ? FOR UPDATE",
                (rs, n) -> new SettlementRow(
                        rs.getString("station_code"),
                        rs.getDate("settle_date").toLocalDate(),
                        rs.getString("request_id"),
                        rs.getBigDecimal("total_flow_m3"),
                        rs.getBigDecimal("theoretical_consume_g"),
                        rs.getBigDecimal("actual_consume_g"),
                        rs.getBigDecimal("deviation_ratio"),
                        rs.getString("status"),
                        rs.getTimestamp("sealed_at") == null ? null
                                : rs.getTimestamp("sealed_at").toLocalDateTime()
                ),
                stationCode, java.sql.Date.valueOf(settleDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertSettlement(SettlementRow row) {
        jdbc.update("INSERT INTO daily_settlement (station_code, settle_date, request_id, total_flow_m3, "
                        + "theoretical_consume_g, actual_consume_g, deviation_ratio, status, sealed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                row.stationCode(), java.sql.Date.valueOf(row.settleDate()), row.requestId(),
                row.totalFlow(), row.theoreticalConsume(), row.actualConsume(), row.deviationRatio(),
                row.status(), row.sealedAt() == null ? null : Timestamp.valueOf(row.sealedAt()));
    }

    /** 对此前转人工（MANUAL）的结算日用当前数据重新复算并更新；SEALED 终态不会被调用到。 */
    public void updateSettlement(SettlementRow row) {
        jdbc.update("UPDATE daily_settlement SET request_id = ?, total_flow_m3 = ?, theoretical_consume_g = ?, "
                        + "actual_consume_g = ?, deviation_ratio = ?, status = ?, sealed_at = ? "
                        + "WHERE station_code = ? AND settle_date = ?",
                row.requestId(), row.totalFlow(), row.theoreticalConsume(), row.actualConsume(),
                row.deviationRatio(), row.status(),
                row.sealedAt() == null ? null : Timestamp.valueOf(row.sealedAt()),
                row.stationCode(), java.sql.Date.valueOf(row.settleDate()));
    }

    /** 当日流量/实耗聚合（半开区间 [dayStart, nextDayStart)）。 */
    public record DayTotals(BigDecimal totalFlow, BigDecimal actualConsume) {
    }

    public DayTotals sumDay(String stationCode, LocalDateTime dayStart, LocalDateTime nextDayStart) {
        BigDecimal totalFlow = jdbc.queryForObject(
                "SELECT COALESCE(SUM(flow_m3), 0) FROM flow_reading "
                        + "WHERE station_code = ? AND detected_at >= ? AND detected_at < ?",
                BigDecimal.class, stationCode, Timestamp.valueOf(dayStart), Timestamp.valueOf(nextDayStart));
        BigDecimal actualConsume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(actual_consume_g), 0) FROM odorant_injection "
                        + "WHERE station_code = ? AND occurred_at >= ? AND occurred_at < ?",
                BigDecimal.class, stationCode, Timestamp.valueOf(dayStart), Timestamp.valueOf(nextDayStart));
        return new DayTotals(totalFlow == null ? BigDecimal.ZERO : totalFlow,
                actualConsume == null ? BigDecimal.ZERO : actualConsume);
    }

    public void insertLateEvent(String stationCode, LocalDate settleDate, String requestId,
                                LocalDateTime detectedAt, String payload) {
        jdbc.update("INSERT INTO late_detection_event (station_code, settle_date, request_id, detected_at, "
                        + "payload, created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, java.sql.Date.valueOf(settleDate), requestId,
                Timestamp.valueOf(detectedAt), payload);
    }

    public int countLateEvents(String stationCode, LocalDate settleDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_detection_event WHERE station_code = ? AND settle_date = ?",
                Integer.class, stationCode, java.sql.Date.valueOf(settleDate));
        return count == null ? 0 : count;
    }

    public void insertStationEvent(String stationCode, EventKind kind, LocalDateTime eventAt, String detail) {
        jdbc.update("INSERT INTO station_event (station_code, event_kind, event_at, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                stationCode, kind.name(), Timestamp.valueOf(eventAt), detail);
    }

    /** 浓度检测明细行（用于浓度曲线复算），按检测时刻升序。 */
    public record DetectionDetailRow(LocalDateTime detectedAt, BigDecimal concentrationMg,
                                     boolean inRange, int violationSeq) {
    }

    public java.util.List<DetectionDetailRow> listDetections(String stationCode,
                                                             LocalDateTime dayStart, LocalDateTime nextDayStart) {
        return jdbc.query("SELECT detected_at, concentration_mg, in_range, violation_seq "
                        + "FROM concentration_detection WHERE station_code = ? "
                        + "AND detected_at >= ? AND detected_at < ? ORDER BY detected_at, id",
                (rs, n) -> new DetectionDetailRow(
                        rs.getTimestamp("detected_at").toLocalDateTime(),
                        rs.getBigDecimal("concentration_mg"),
                        rs.getBoolean("in_range"),
                        rs.getInt("violation_seq")),
                stationCode, Timestamp.valueOf(dayStart), Timestamp.valueOf(nextDayStart));
    }

    /** 压力检测明细行（用于压力链复算），按检测时刻升序。 */
    public record PressureDetailRow(LocalDateTime detectedAt, BigDecimal pressureKpa, PressureAction action) {
    }

    public java.util.List<PressureDetailRow> listPressureDetections(String stationCode,
                                                                    LocalDateTime dayStart,
                                                                    LocalDateTime nextDayStart) {
        return jdbc.query("SELECT detected_at, pressure_kpa, pressure_action FROM pressure_detection "
                        + "WHERE station_code = ? AND detected_at >= ? AND detected_at < ? "
                        + "ORDER BY detected_at, id",
                (rs, n) -> new PressureDetailRow(
                        rs.getTimestamp("detected_at").toLocalDateTime(),
                        rs.getBigDecimal("pressure_kpa"),
                        PressureAction.valueOf(rs.getString("pressure_action"))),
                stationCode, Timestamp.valueOf(dayStart), Timestamp.valueOf(nextDayStart));
    }

    /** 查询某日双人复开生效时刻；复开会清零连续越限计数，曲线复算时需要在这些点重置。 */
    public java.util.List<LocalDateTime> listReopenTimes(String stationCode,
                                                         LocalDateTime dayStart, LocalDateTime nextDayStart) {
        return jdbc.query("SELECT event_at FROM station_event WHERE station_code = ? AND event_kind = 'VALVE_REOPENED' "
                        + "AND event_at >= ? AND event_at < ? ORDER BY event_at, id",
                (rs, n) -> rs.getTimestamp("event_at").toLocalDateTime(),
                stationCode, Timestamp.valueOf(dayStart), Timestamp.valueOf(nextDayStart));
    }
}
