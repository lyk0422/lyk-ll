package com.example.starter.dr.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 需求响应聚合的数据访问层。所有写操作均在调用方声明的事务内执行；
 * 名称含 lock 的方法使用 {@code SELECT ... FOR UPDATE}，必须在事务内调用。
 */
@Repository
public class DrRepository {

    private final JdbcTemplate jdbc;

    public DrRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 事件行。 */
    public record EventRow(Long id, String eventRef, Instant startUtc, Instant endUtc,
                           int intervalMinutes, int version) {
    }

    /** 电表登记行。 */
    public record EnrollmentRow(Long id, Long eventId, String meterId,
                                BigDecimal committedValue, String baselineVersion) {
    }

    /** 曲线点行。 */
    public record PointRow(Long id, Long eventId, String meterId, String dataType,
                           int intervalIdx, BigDecimal value, int version) {
    }

    /** 冻结头行。 */
    public record FreezeRow(Long id, Long eventId, String requestId, int expectedVersion,
                            String fingerprint, BigDecimal totalDelivered, Instant frozenAt) {
    }

    /** 冻结逐区间快照行。 */
    public record FrozenIntervalRow(Long id, Long freezeId, String meterId, int intervalIdx,
                                    BigDecimal baselineValue, BigDecimal actualValue,
                                    int baselineVersion, int actualVersion,
                                    BigDecimal deliveredValue) {
    }

    /** 冻结后更正建议行。 */
    public record AdjustmentRow(Long id, Long eventId, Long freezeId, String meterId, int intervalIdx,
                                String dataType, int frozenVersion, BigDecimal frozenValue,
                                int correctedVersion, BigDecimal correctedValue,
                                BigDecimal frozenIntervalDelivered, BigDecimal suggestedIntervalDelivered,
                                BigDecimal suggestedDelta, String status, Instant createdAt) {
    }

    // ---------- 事件 ----------

    /** 新增事件，返回带自增主键的行。 */
    public EventRow insertEvent(EventRow row, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dr_event (event_ref, start_time, end_time, interval_minutes, version, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.eventRef());
            ps.setTimestamp(2, Timestamp.from(row.startUtc()));
            ps.setTimestamp(3, Timestamp.from(row.endUtc()));
            ps.setInt(4, row.intervalMinutes());
            ps.setInt(5, row.version());
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new EventRow(id, row.eventRef(), row.startUtc(), row.endUtc(),
                row.intervalMinutes(), row.version());
    }

    public Optional<EventRow> findEventByRef(String eventRef) {
        List<EventRow> rows = jdbc.query(
                "SELECT id, event_ref, start_time, end_time, interval_minutes, version "
                        + "FROM dr_event WHERE event_ref = ?",
                EVENT_MAPPER, eventRef);
        return rows.stream().findFirst();
    }

    /** 按事件编码加行锁（FOR UPDATE），必须在事务内调用。 */
    public Optional<EventRow> lockEventByRef(String eventRef) {
        List<EventRow> rows = jdbc.query(
                "SELECT id, event_ref, start_time, end_time, interval_minutes, version "
                        + "FROM dr_event WHERE event_ref = ? FOR UPDATE",
                EVENT_MAPPER, eventRef);
        return rows.stream().findFirst();
    }

    // ---------- 登记 ----------

    public long insertEnrollment(EnrollmentRow row, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dr_enrollment "
                            + "(event_id, meter_id, committed_value, baseline_version, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.eventId());
            ps.setString(2, row.meterId());
            ps.setBigDecimal(3, row.committedValue());
            ps.setString(4, row.baselineVersion());
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<EnrollmentRow> findEnrollment(long eventId, String meterId) {
        List<EnrollmentRow> rows = jdbc.query(
                "SELECT id, event_id, meter_id, committed_value, baseline_version "
                        + "FROM dr_enrollment WHERE event_id = ? AND meter_id = ?",
                ENROLLMENT_MAPPER, eventId, meterId);
        return rows.stream().findFirst();
    }

    public List<EnrollmentRow> findEnrollmentsByEvent(long eventId) {
        return jdbc.query(
                "SELECT id, event_id, meter_id, committed_value, baseline_version "
                        + "FROM dr_enrollment WHERE event_id = ? ORDER BY id",
                ENROLLMENT_MAPPER, eventId);
    }

    /**
     * 按主键顺序锁定全部事件行（FOR UPDATE）。登记事务借此串行化：
     * 任何登记都先获取全表事件锁，再判断时间重叠，避免新电表无既有行可锁导致的漏判，
     * 也保证所有登记事务以相同顺序加锁、不会互锁。必须在事务内调用。
     */
    public List<Long> lockAllEventRows() {
        return jdbc.queryForList("SELECT id FROM dr_event ORDER BY id FOR UPDATE", Long.class);
    }

    /**
     * 查询某电表已登记的、与给定半开区间时间重叠的事件登记行。
     * 重叠判定 {@code ev.start < 新end 且 ev.end > 新start}，端点相接（相等）不算重叠。
     * 调用方须已通过 {@link #lockAllEventRows()} 完成登记串行化。
     */
    public List<EnrollmentRow> findOverlappingEnrollments(String meterId, Instant startUtc, Instant endUtc) {
        return jdbc.query(
                "SELECT e.id, e.event_id, e.meter_id, e.committed_value, e.baseline_version "
                        + "FROM dr_enrollment e JOIN dr_event ev ON e.event_id = ev.id "
                        + "WHERE e.meter_id = ? AND ev.start_time < ? AND ev.end_time > ?",
                ENROLLMENT_MAPPER, meterId, Timestamp.from(endUtc), Timestamp.from(startUtc));
    }

    public void incrementEventVersion(long eventId) {
        int updated = jdbc.update("UPDATE dr_event SET version = version + 1 WHERE id = ?", eventId);
        if (updated != 1) {
            throw new IllegalStateException("事件版本自增失败，eventId=" + eventId);
        }
    }

    public boolean isEventFrozen(long eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dr_settlement_freeze WHERE event_id = ?",
                Integer.class, eventId);
        return count != null && count > 0;
    }

    // ---------- 曲线点 ----------

    public void insertPoint(PointRow row, Instant now) {
        jdbc.update("INSERT INTO dr_curve_point "
                        + "(event_id, meter_id, data_type, interval_idx, point_value, version, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.eventId(), row.meterId(), row.dataType(), row.intervalIdx(),
                row.value(), row.version(), Timestamp.from(now));
    }

    public Optional<PointRow> findPoint(long eventId, String meterId, String dataType, int intervalIdx) {
        List<PointRow> rows = jdbc.query(
                "SELECT id, event_id, meter_id, data_type, interval_idx, point_value, version "
                        + "FROM dr_curve_point "
                        + "WHERE event_id = ? AND meter_id = ? AND data_type = ? AND interval_idx = ?",
                POINT_MAPPER, eventId, meterId, dataType, intervalIdx);
        return rows.stream().findFirst();
    }

    /** 对单个曲线点加行锁（FOR UPDATE），必须在事务内调用。 */
    public Optional<PointRow> lockPoint(long eventId, String meterId, String dataType, int intervalIdx) {
        List<PointRow> rows = jdbc.query(
                "SELECT id, event_id, meter_id, data_type, interval_idx, point_value, version "
                        + "FROM dr_curve_point "
                        + "WHERE event_id = ? AND meter_id = ? AND data_type = ? AND interval_idx = ? "
                        + "FOR UPDATE",
                POINT_MAPPER, eventId, meterId, dataType, intervalIdx);
        return rows.stream().findFirst();
    }

    /** 乐观更正：仅当前版本等于 expectedVersion 时生效，返回受影响行数（0 表示版本冲突）。 */
    public int casUpdatePoint(long pointId, BigDecimal newValue, int expectedVersion, int newVersion, Instant now) {
        return jdbc.update("UPDATE dr_curve_point "
                        + "SET point_value = ?, version = ?, updated_at = ? "
                        + "WHERE id = ? AND version = ?",
                newValue, newVersion, Timestamp.from(now), pointId, expectedVersion);
    }

    // ---------- 冻结 ----------

    public Optional<FreezeRow> findFreezeByRequestId(String requestId) {
        List<FreezeRow> rows = jdbc.query(
                "SELECT id, event_id, request_id, expected_version, fingerprint, total_delivered, frozen_at "
                        + "FROM dr_settlement_freeze WHERE request_id = ?",
                FREEZE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public Optional<FreezeRow> findFreezeByEvent(long eventId) {
        List<FreezeRow> rows = jdbc.query(
                "SELECT id, event_id, request_id, expected_version, fingerprint, total_delivered, frozen_at "
                        + "FROM dr_settlement_freeze WHERE event_id = ?",
                FREEZE_MAPPER, eventId);
        return rows.stream().findFirst();
    }

    /** 新增冻结头并回填自增主键。 */
    public FreezeRow insertFreeze(FreezeRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dr_settlement_freeze "
                            + "(event_id, request_id, expected_version, fingerprint, total_delivered, frozen_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.eventId());
            ps.setString(2, row.requestId());
            ps.setInt(3, row.expectedVersion());
            ps.setString(4, row.fingerprint());
            ps.setBigDecimal(5, row.totalDelivered());
            ps.setTimestamp(6, Timestamp.from(row.frozenAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new FreezeRow(id, row.eventId(), row.requestId(), row.expectedVersion(),
                row.fingerprint(), row.totalDelivered(), row.frozenAt());
    }

    public void insertFrozenInterval(FrozenIntervalRow row) {
        jdbc.update("INSERT INTO dr_freeze_interval "
                        + "(freeze_id, meter_id, interval_idx, baseline_value, actual_value, "
                        + " baseline_version, actual_version, delivered_value) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.freezeId(), row.meterId(), row.intervalIdx(),
                row.baselineValue(), row.actualValue(),
                row.baselineVersion(), row.actualVersion(), row.deliveredValue());
    }

    public List<FrozenIntervalRow> findFrozenIntervals(long freezeId) {
        return jdbc.query(
                "SELECT id, freeze_id, meter_id, interval_idx, baseline_value, actual_value, "
                        + "baseline_version, actual_version, delivered_value "
                        + "FROM dr_freeze_interval WHERE freeze_id = ? ORDER BY meter_id, interval_idx",
                FROZEN_INTERVAL_MAPPER, freezeId);
    }

    public Optional<FrozenIntervalRow> findFrozenInterval(long freezeId, String meterId, int intervalIdx) {
        List<FrozenIntervalRow> rows = jdbc.query(
                "SELECT id, freeze_id, meter_id, interval_idx, baseline_value, actual_value, "
                        + "baseline_version, actual_version, delivered_value "
                        + "FROM dr_freeze_interval WHERE freeze_id = ? AND meter_id = ? AND interval_idx = ?",
                FROZEN_INTERVAL_MAPPER, freezeId, meterId, intervalIdx);
        return rows.stream().findFirst();
    }

    // ---------- 调整建议 ----------

    public Optional<AdjustmentRow> findOpenAdjustment(long freezeId, String meterId,
                                                      int intervalIdx, String dataType) {
        List<AdjustmentRow> rows = jdbc.query(
                "SELECT id, event_id, freeze_id, meter_id, interval_idx, data_type, frozen_version, "
                        + "frozen_value, corrected_version, corrected_value, frozen_interval_delivered, "
                        + "suggested_interval_delivered, suggested_delta, status, created_at "
                        + "FROM dr_adjustment "
                        + "WHERE freeze_id = ? AND meter_id = ? AND interval_idx = ? AND data_type = ? "
                        + "AND status = 'OPEN'",
                ADJUSTMENT_MAPPER, freezeId, meterId, intervalIdx, dataType);
        return rows.stream().findFirst();
    }

    public void insertAdjustment(AdjustmentRow row) {
        jdbc.update("INSERT INTO dr_adjustment "
                        + "(event_id, freeze_id, meter_id, interval_idx, data_type, frozen_version, "
                        + " frozen_value, corrected_version, corrected_value, frozen_interval_delivered, "
                        + " suggested_interval_delivered, suggested_delta, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.eventId(), row.freezeId(), row.meterId(), row.intervalIdx(), row.dataType(),
                row.frozenVersion(), row.frozenValue(), row.correctedVersion(), row.correctedValue(),
                row.frozenIntervalDelivered(), row.suggestedIntervalDelivered(), row.suggestedDelta(),
                row.status(), Timestamp.from(row.createdAt()));
    }

    public void updateAdjustment(AdjustmentRow row) {
        int updated = jdbc.update("UPDATE dr_adjustment "
                        + "SET frozen_version = ?, frozen_value = ?, corrected_version = ?, corrected_value = ?, "
                        + "    frozen_interval_delivered = ?, suggested_interval_delivered = ?, suggested_delta = ? "
                        + "WHERE id = ?",
                row.frozenVersion(), row.frozenValue(), row.correctedVersion(), row.correctedValue(),
                row.frozenIntervalDelivered(), row.suggestedIntervalDelivered(), row.suggestedDelta(),
                row.id());
        if (updated != 1) {
            throw new IllegalStateException("调整建议更新失败，id=" + row.id());
        }
    }

    public List<AdjustmentRow> findAdjustmentsByEvent(long eventId) {
        return jdbc.query(
                "SELECT id, event_id, freeze_id, meter_id, interval_idx, data_type, frozen_version, "
                        + "frozen_value, corrected_version, corrected_value, frozen_interval_delivered, "
                        + "suggested_interval_delivered, suggested_delta, status, created_at "
                        + "FROM dr_adjustment WHERE event_id = ? ORDER BY id",
                ADJUSTMENT_MAPPER, eventId);
    }

    // ---------- RowMapper ----------

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, n) -> new EventRow(
            rs.getLong("id"),
            rs.getString("event_ref"),
            rs.getTimestamp("start_time").toInstant(),
            rs.getTimestamp("end_time").toInstant(),
            rs.getInt("interval_minutes"),
            rs.getInt("version"));

    private static final RowMapper<EnrollmentRow> ENROLLMENT_MAPPER = (rs, n) -> new EnrollmentRow(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getString("meter_id"),
            rs.getBigDecimal("committed_value"),
            rs.getString("baseline_version"));

    private static final RowMapper<PointRow> POINT_MAPPER = (rs, n) -> new PointRow(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getString("meter_id"),
            rs.getString("data_type"),
            rs.getInt("interval_idx"),
            rs.getBigDecimal("point_value"),
            rs.getInt("version"));

    private static final RowMapper<FreezeRow> FREEZE_MAPPER = (rs, n) -> new FreezeRow(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getString("request_id"),
            rs.getInt("expected_version"),
            rs.getString("fingerprint"),
            rs.getBigDecimal("total_delivered"),
            rs.getTimestamp("frozen_at").toInstant());

    private static final RowMapper<FrozenIntervalRow> FROZEN_INTERVAL_MAPPER = (rs, n) -> new FrozenIntervalRow(
            rs.getLong("id"),
            rs.getLong("freeze_id"),
            rs.getString("meter_id"),
            rs.getInt("interval_idx"),
            rs.getBigDecimal("baseline_value"),
            rs.getBigDecimal("actual_value"),
            rs.getInt("baseline_version"),
            rs.getInt("actual_version"),
            rs.getBigDecimal("delivered_value"));

    private static final RowMapper<AdjustmentRow> ADJUSTMENT_MAPPER = (rs, n) -> new AdjustmentRow(
            rs.getLong("id"),
            rs.getLong("event_id"),
            rs.getLong("freeze_id"),
            rs.getString("meter_id"),
            rs.getInt("interval_idx"),
            rs.getString("data_type"),
            rs.getInt("frozen_version"),
            rs.getBigDecimal("frozen_value"),
            rs.getInt("corrected_version"),
            rs.getBigDecimal("corrected_value"),
            rs.getBigDecimal("frozen_interval_delivered"),
            rs.getBigDecimal("suggested_interval_delivered"),
            rs.getBigDecimal("suggested_delta"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());
}
