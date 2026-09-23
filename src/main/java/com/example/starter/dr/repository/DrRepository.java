package com.example.starter.dr.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.dr.domain.Rows;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 需求响应数据访问层：全部使用参数化 SQL，时间以 UTC（TIMESTAMP）存储。
 */
@Repository
public class DrRepository {

    private final JdbcTemplate jdbcTemplate;

    public DrRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    // ---------- 事件 ----------

    /** 插入事件；事件 ID 重复时由唯一约束抛出 {@link DuplicateKeyException}。 */
    public void insertEvent(String eventId, Instant start, Instant end, int intervalMinutes, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO dr_event (event_id, start_time, end_time, interval_minutes, version, frozen, created_at)"
                        + " VALUES (?,?,?,?,1,0,?)",
                eventId, Timestamp.from(start), Timestamp.from(end), intervalMinutes, Timestamp.from(now));
    }

    public Optional<Rows.EventRow> findEvent(String eventId) {
        List<Rows.EventRow> list = jdbcTemplate.query(
                "SELECT event_id, start_time, end_time, interval_minutes, version, frozen FROM dr_event WHERE event_id=?",
                EVENT_MAPPER, eventId);
        return list.stream().findFirst();
    }

    /** 行锁读取事件（须在事务内调用），用于冻结时的版本与并发保护。 */
    public Optional<Rows.EventRow> findEventForUpdate(String eventId) {
        List<Rows.EventRow> list = jdbcTemplate.query(
                "SELECT event_id, start_time, end_time, interval_minutes, version, frozen"
                        + " FROM dr_event WHERE event_id=? FOR UPDATE",
                EVENT_MAPPER, eventId);
        return list.stream().findFirst();
    }

    /** 事件版本号 +1，并返回新值（行锁内调用）。 */
    public int incrementEventVersion(String eventId) {
        jdbcTemplate.update("UPDATE dr_event SET version=version+1 WHERE event_id=?", eventId);
        return findEvent(eventId).orElseThrow().version();
    }

    public void markEventFrozen(String eventId) {
        jdbcTemplate.update("UPDATE dr_event SET frozen=1 WHERE event_id=?", eventId);
    }

    /** 查询与给定半开区间 [start,end) 时间重叠、且该电表已登记的事件；端点相接不算重叠。 */
    public List<Rows.EventRow> findOverlappingEventsForMeter(String meterId, Instant start, Instant end) {
        return jdbcTemplate.query(
                "SELECT e.event_id, e.start_time, e.end_time, e.interval_minutes, e.version, e.frozen"
                        + " FROM dr_event e"
                        + " JOIN dr_enrollment en ON en.event_id = e.event_id"
                        + " WHERE en.meter_id = ? AND e.start_time < ? AND e.end_time > ?",
                EVENT_MAPPER, meterId, Timestamp.from(end), Timestamp.from(start));
    }

    // ---------- 登记 ----------

    public void insertEnrollment(String eventId, String meterId, BigDecimal committedKw,
                                 String baselineVersion, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO dr_enrollment (event_id, meter_id, committed_kw, baseline_version, created_at)"
                        + " VALUES (?,?,?,?,?)",
                eventId, meterId, committedKw, baselineVersion, Timestamp.from(now));
    }

    public List<Rows.EnrollmentRow> findEnrollments(String eventId) {
        return jdbcTemplate.query(
                "SELECT event_id, meter_id, committed_kw, baseline_version FROM dr_enrollment WHERE event_id=?"
                        + " ORDER BY meter_id",
                ENROLLMENT_MAPPER, eventId);
    }

    public Optional<Rows.EnrollmentRow> findEnrollment(String eventId, String meterId) {
        List<Rows.EnrollmentRow> list = jdbcTemplate.query(
                "SELECT event_id, meter_id, committed_kw, baseline_version FROM dr_enrollment"
                        + " WHERE event_id=? AND meter_id=?",
                ENROLLMENT_MAPPER, eventId, meterId);
        return list.stream().findFirst();
    }

    /** 锁定某电表在全部事件中的登记行（事务内调用），串行化同电表的并发登记以校验时间重叠。 */
    public void lockEnrollmentsForMeter(String meterId) {
        jdbcTemplate.query(
                "SELECT event_id, meter_id, committed_kw, baseline_version FROM dr_enrollment"
                        + " WHERE meter_id=? FOR UPDATE",
                ENROLLMENT_MAPPER, meterId);
    }

    // ---------- 曲线 ----------

    public Optional<Rows.CurvePointRow> findCurvePoint(String eventId, String meterId,
                                                       int intervalIndex, String dataType) {
        List<Rows.CurvePointRow> list = jdbcTemplate.query(
                "SELECT event_id, meter_id, interval_index, data_type, point_value, version"
                        + " FROM curve_point_current WHERE event_id=? AND meter_id=? AND interval_index=? AND data_type=?",
                CURVE_MAPPER, eventId, meterId, intervalIndex, dataType);
        return list.stream().findFirst();
    }

    /** 行锁读取曲线当前值（事务内调用），用于检测并发更正。 */
    public Optional<Rows.CurvePointRow> findCurvePointForUpdate(String eventId, String meterId,
                                                                int intervalIndex, String dataType) {
        List<Rows.CurvePointRow> list = jdbcTemplate.query(
                "SELECT event_id, meter_id, interval_index, data_type, point_value, version FROM curve_point_current"
                        + " WHERE event_id=? AND meter_id=? AND interval_index=? AND data_type=? FOR UPDATE",
                CURVE_MAPPER, eventId, meterId, intervalIndex, dataType);
        return list.stream().findFirst();
    }

    public List<Rows.CurvePointRow> findCurvePoints(String eventId, String meterId, String dataType) {
        return jdbcTemplate.query(
                "SELECT event_id, meter_id, interval_index, data_type, point_value, version FROM curve_point_current"
                        + " WHERE event_id=? AND meter_id=? AND data_type=? ORDER BY interval_index",
                CURVE_MAPPER, eventId, meterId, dataType);
    }

    public void insertCurveCurrent(String eventId, String meterId, int intervalIndex, String dataType,
                                   BigDecimal value, int version, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO curve_point_current (event_id, meter_id, interval_index, data_type, point_value, version, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                eventId, meterId, intervalIndex, dataType, value, version, Timestamp.from(now));
    }

    /** 乐观更正：仅当前版本等于 expectedVersion 时更新成功。 */
    public int updateCurveCurrent(String eventId, String meterId, int intervalIndex, String dataType,
                                  BigDecimal value, int newVersion, int expectedVersion, Instant now) {
        return jdbcTemplate.update(
                "UPDATE curve_point_current SET point_value=?, version=?, updated_at=?"
                        + " WHERE event_id=? AND meter_id=? AND interval_index=? AND data_type=? AND version=?",
                value, newVersion, Timestamp.from(now),
                eventId, meterId, intervalIndex, dataType, expectedVersion);
    }

    public void insertCurveHistory(String eventId, String meterId, int intervalIndex, String dataType,
                                   int version, BigDecimal value, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO curve_point_history (event_id, meter_id, interval_index, data_type, version, point_value, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                eventId, meterId, intervalIndex, dataType, version, value, Timestamp.from(now));
    }

    // ---------- 冻结/结算 ----------

    public void insertFreezeRequest(String requestId, String eventId, String fingerprint,
                                    String settlementId, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO freeze_request (request_id, event_id, fingerprint, settlement_id, created_at)"
                        + " VALUES (?,?,?,?,?)",
                requestId, eventId, fingerprint, settlementId, Timestamp.from(now));
    }

    public Optional<Rows.FreezeRequestRow> findFreezeRequest(String requestId) {
        List<Rows.FreezeRequestRow> list = jdbcTemplate.query(
                "SELECT request_id, event_id, fingerprint, settlement_id, created_at FROM freeze_request WHERE request_id=?",
                FREEZE_REQUEST_MAPPER, requestId);
        return list.stream().findFirst();
    }

    /** 行锁读取幂等请求（事务内调用）。 */
    public Optional<Rows.FreezeRequestRow> findFreezeRequestForUpdate(String requestId) {
        List<Rows.FreezeRequestRow> list = jdbcTemplate.query(
                "SELECT request_id, event_id, fingerprint, settlement_id, created_at FROM freeze_request"
                        + " WHERE request_id=? FOR UPDATE",
                FREEZE_REQUEST_MAPPER, requestId);
        return list.stream().findFirst();
    }

    public Optional<Rows.SettlementRow> findSettlementByEvent(String eventId) {
        List<Rows.SettlementRow> list = jdbcTemplate.query(
                "SELECT settlement_id, event_id, request_id, event_version, total_delivered_kw, frozen_at"
                        + " FROM settlement WHERE event_id=?",
                SETTLEMENT_MAPPER, eventId);
        return list.stream().findFirst();
    }

    public Optional<Rows.SettlementRow> findSettlement(String settlementId) {
        List<Rows.SettlementRow> list = jdbcTemplate.query(
                "SELECT settlement_id, event_id, request_id, event_version, total_delivered_kw, frozen_at"
                        + " FROM settlement WHERE settlement_id=?",
                SETTLEMENT_MAPPER, settlementId);
        return list.stream().findFirst();
    }

    public void insertSettlement(Rows.SettlementRow row, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO settlement (settlement_id, event_id, request_id, event_version, total_delivered_kw, frozen_at)"
                        + " VALUES (?,?,?,?,?,?)",
                row.settlementId(), row.eventId(), row.requestId(), row.eventVersion(),
                row.totalDeliveredKw(), Timestamp.from(row.frozenAt() != null ? row.frozenAt() : now));
    }

    public void insertSettlementInterval(Rows.SettlementIntervalRow row) {
        jdbcTemplate.update(
                "INSERT INTO settlement_interval (settlement_id, event_id, meter_id, interval_index, interval_start,"
                        + " baseline_value, actual_value, baseline_version, actual_version, delivered_kw)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.settlementId(), row.eventId(), row.meterId(), row.intervalIndex(),
                Timestamp.from(row.intervalStart()), row.baselineValue(), row.actualValue(),
                row.baselineVersion(), row.actualVersion(), row.deliveredKw());
    }

    public List<Rows.SettlementIntervalRow> findSettlementIntervals(String settlementId) {
        return jdbcTemplate.query(
                "SELECT settlement_id, event_id, meter_id, interval_index, interval_start, baseline_value,"
                        + " actual_value, baseline_version, actual_version, delivered_kw"
                        + " FROM settlement_interval WHERE settlement_id=? ORDER BY meter_id, interval_index",
                INTERVAL_MAPPER, settlementId);
    }

    public Optional<Rows.SettlementIntervalRow> findSettlementInterval(String settlementId, String meterId,
                                                                        int intervalIndex) {
        List<Rows.SettlementIntervalRow> list = jdbcTemplate.query(
                "SELECT settlement_id, event_id, meter_id, interval_index, interval_start, baseline_value,"
                        + " actual_value, baseline_version, actual_version, delivered_kw"
                        + " FROM settlement_interval WHERE settlement_id=? AND meter_id=? AND interval_index=?",
                INTERVAL_MAPPER, settlementId, meterId, intervalIndex);
        return list.stream().findFirst();
    }

    // ---------- 调整建议 ----------

    public Optional<Rows.AdjustmentRow> findAdjustment(String settlementId, String meterId, int intervalIndex) {
        List<Rows.AdjustmentRow> list = jdbcTemplate.query(
                "SELECT id, settlement_id, event_id, meter_id, interval_index, frozen_delivered_kw,"
                        + " revised_baseline_kw, revised_actual_kw, revised_delivered_kw, delta_kw,"
                        + " baseline_version, actual_version, created_at, updated_at"
                        + " FROM adjustment_advice WHERE settlement_id=? AND meter_id=? AND interval_index=?",
                ADJUSTMENT_MAPPER, settlementId, meterId, intervalIndex);
        return list.stream().findFirst();
    }

    public List<Rows.AdjustmentRow> findAdjustments(String settlementId) {
        return jdbcTemplate.query(
                "SELECT id, settlement_id, event_id, meter_id, interval_index, frozen_delivered_kw,"
                        + " revised_baseline_kw, revised_actual_kw, revised_delivered_kw, delta_kw,"
                        + " baseline_version, actual_version, created_at, updated_at"
                        + " FROM adjustment_advice WHERE settlement_id=? ORDER BY meter_id, interval_index",
                ADJUSTMENT_MAPPER, settlementId);
    }

    public void insertAdjustment(Rows.AdjustmentRow row, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO adjustment_advice (settlement_id, event_id, meter_id, interval_index, frozen_delivered_kw,"
                        + " revised_baseline_kw, revised_actual_kw, revised_delivered_kw, delta_kw,"
                        + " baseline_version, actual_version, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.settlementId(), row.eventId(), row.meterId(), row.intervalIndex(), row.frozenDeliveredKw(),
                row.revisedBaselineKw(), row.revisedActualKw(), row.revisedDeliveredKw(), row.deltaKw(),
                row.baselineVersion(), row.actualVersion(), Timestamp.from(now), Timestamp.from(now));
    }

    public int updateAdjustment(long id, BigDecimal revisedBaseline, BigDecimal revisedActual,
                                BigDecimal revisedDelivered, BigDecimal delta,
                                Integer baselineVersion, Integer actualVersion, Instant now) {
        return jdbcTemplate.update(
                "UPDATE adjustment_advice SET revised_baseline_kw=?, revised_actual_kw=?,"
                        + " revised_delivered_kw=?, delta_kw=?, baseline_version=?, actual_version=?, updated_at=?"
                        + " WHERE id=?",
                revisedBaseline, revisedActual, revisedDelivered, delta,
                baselineVersion, actualVersion, Timestamp.from(now), id);
    }

    // ---------- RowMapper ----------

    private static final RowMapper<Rows.EventRow> EVENT_MAPPER = (rs, n) -> new Rows.EventRow(
            rs.getString("event_id"),
            rs.getTimestamp("start_time").toInstant(),
            rs.getTimestamp("end_time").toInstant(),
            rs.getInt("interval_minutes"),
            rs.getInt("version"),
            rs.getInt("frozen") == 1);

    private static final RowMapper<Rows.EnrollmentRow> ENROLLMENT_MAPPER = (rs, n) -> new Rows.EnrollmentRow(
            rs.getString("event_id"),
            rs.getString("meter_id"),
            rs.getBigDecimal("committed_kw"),
            rs.getString("baseline_version"));

    private static final RowMapper<Rows.CurvePointRow> CURVE_MAPPER = new RowMapper<>() {
        @Override
        public Rows.CurvePointRow mapRow(ResultSet rs, int n) throws SQLException {
            return new Rows.CurvePointRow(
                    rs.getString("event_id"),
                    rs.getString("meter_id"),
                    rs.getInt("interval_index"),
                    rs.getString("data_type"),
                    rs.getBigDecimal("point_value"),
                    rs.getInt("version"));
        }
    };

    private static final RowMapper<Rows.FreezeRequestRow> FREEZE_REQUEST_MAPPER = (rs, n) -> new Rows.FreezeRequestRow(
            rs.getString("request_id"),
            rs.getString("event_id"),
            rs.getString("fingerprint"),
            rs.getString("settlement_id"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Rows.SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new Rows.SettlementRow(
            rs.getString("settlement_id"),
            rs.getString("event_id"),
            rs.getString("request_id"),
            rs.getInt("event_version"),
            rs.getBigDecimal("total_delivered_kw"),
            rs.getTimestamp("frozen_at").toInstant());

    private static final RowMapper<Rows.SettlementIntervalRow> INTERVAL_MAPPER = (rs, n) ->
            new Rows.SettlementIntervalRow(
                    rs.getString("settlement_id"),
                    rs.getString("event_id"),
                    rs.getString("meter_id"),
                    rs.getInt("interval_index"),
                    rs.getTimestamp("interval_start").toInstant(),
                    rs.getBigDecimal("baseline_value"),
                    rs.getBigDecimal("actual_value"),
                    rs.getInt("baseline_version"),
                    rs.getInt("actual_version"),
                    rs.getBigDecimal("delivered_kw"));

    private static final RowMapper<Rows.AdjustmentRow> ADJUSTMENT_MAPPER = (rs, n) -> new Rows.AdjustmentRow(
            rs.getLong("id"),
            rs.getString("settlement_id"),
            rs.getString("event_id"),
            rs.getString("meter_id"),
            rs.getInt("interval_index"),
            rs.getBigDecimal("frozen_delivered_kw"),
            rs.getBigDecimal("revised_baseline_kw"),
            rs.getBigDecimal("revised_actual_kw"),
            rs.getBigDecimal("revised_delivered_kw"),
            rs.getBigDecimal("delta_kw"),
            nullableInt(rs, "baseline_version"),
            nullableInt(rs, "actual_version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
