package com.example.starter.hazmat.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.hazmat.domain.EscortRole;
import com.example.starter.hazmat.domain.RelaySegmentStatus;
import com.example.starter.hazmat.domain.RouteApprovalStatus;
import com.example.starter.hazmat.domain.ShipmentStatus;
import com.example.starter.hazmat.domain.WaypointState;
import com.example.starter.hazmat.po.ApprovalWaypoint;
import com.example.starter.hazmat.po.Checkin;
import com.example.starter.hazmat.po.ControlPeriod;
import com.example.starter.hazmat.po.DisposalRecord;
import com.example.starter.hazmat.po.EscortQualification;
import com.example.starter.hazmat.po.EscortReservation;
import com.example.starter.hazmat.po.GoodsCategory;
import com.example.starter.hazmat.po.IdempotentRecord;
import com.example.starter.hazmat.po.NightCurfew;
import com.example.starter.hazmat.po.RelayCheckin;
import com.example.starter.hazmat.po.RelaySegment;
import com.example.starter.hazmat.po.RouteApproval;
import com.example.starter.hazmat.po.RouteInfo;
import com.example.starter.hazmat.po.RouteWaypointDef;
import com.example.starter.hazmat.po.Shipment;
import com.example.starter.hazmat.po.ShipmentEvent;
import com.example.starter.hazmat.po.Vehicle;

/**
 * 危化品监管数据访问层（H2 MODE=MySQL，全部参数化 SQL）。
 *
 * <p>并发原语：
 * <ul>
 *   <li>单车占用表 {@code vehicle_active_shipment} 主键冲突即并发申请失败；</li>
 *   <li>运单终态化使用条件更新 {@code WHERE is_terminal=0}，保证核销/失联唯一终态；</li>
 *   <li>途经点状态迁移使用条件更新，避免监控扫描与打卡互相覆盖。</li>
 * </ul>
 */
@Repository
public class HazmatRepository {

    private final JdbcTemplate jdbc;

    public HazmatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 基础数据 ----------

    private static final RowMapper<GoodsCategory> CATEGORY_MAPPER = (rs, n) ->
            new GoodsCategory(rs.getString("category_code"),
                    rs.getString("category_name"), rs.getInt("required_level"));

    public Optional<GoodsCategory> findCategory(String code) {
        return jdbc.query("SELECT category_code, category_name, required_level FROM goods_category WHERE category_code = ?",
                CATEGORY_MAPPER, code).stream().findFirst();
    }

    private static final RowMapper<RouteInfo> ROUTE_MAPPER = (rs, n) ->
            new RouteInfo(rs.getString("route_code"), rs.getString("route_name"),
                    rs.getInt("pass_level"), rs.getInt("forbidden") == 1);

    public Optional<RouteInfo> findRoute(String code) {
        return jdbc.query("SELECT route_code, route_name, pass_level, forbidden FROM route_info WHERE route_code = ?",
                ROUTE_MAPPER, code).stream().findFirst();
    }

    private static final RowMapper<RouteWaypointDef> WAYPOINT_DEF_MAPPER = (rs, n) ->
            new RouteWaypointDef(rs.getString("route_code"), rs.getInt("seq"),
                    rs.getString("name"), rs.getInt("offset_minutes"));

    public List<RouteWaypointDef> findWaypointDefs(String routeCode) {
        return jdbc.query("SELECT route_code, seq, name, offset_minutes FROM route_waypoint_def WHERE route_code = ? ORDER BY seq",
                WAYPOINT_DEF_MAPPER, routeCode);
    }

    private static final RowMapper<ControlPeriod> CONTROL_MAPPER = (rs, n) ->
            new ControlPeriod(rs.getLong("id"), rs.getString("route_code"),
                    rs.getTimestamp("start_time").toLocalDateTime(),
                    rs.getTimestamp("end_time").toLocalDateTime());

    public List<ControlPeriod> findControlPeriods(String routeCode) {
        return jdbc.query("SELECT id, route_code, start_time, end_time FROM control_period WHERE route_code = ? ORDER BY start_time",
                CONTROL_MAPPER, routeCode);
    }

    // ---------- 车辆 ----------

    private static final RowMapper<Vehicle> VEHICLE_MAPPER = (rs, n) -> new Vehicle(
            rs.getString("plate"), rs.getInt("frozen") == 1,
            (Long) rs.getObject("frozen_shipment_id"),
            rs.getTimestamp("frozen_at") == null ? null : rs.getTimestamp("frozen_at").toLocalDateTime());

    public Optional<Vehicle> findVehicle(String plate) {
        return jdbc.query("SELECT plate, frozen, frozen_shipment_id, frozen_at FROM vehicle WHERE plate = ?",
                VEHICLE_MAPPER, plate).stream().findFirst();
    }

    /** 冻结车辆（失联升级时），幂等条件：仅未冻结车辆生效，返回受影响行数。 */
    public int freezeVehicle(String plate, long shipmentId, LocalDateTime now) {
        return jdbc.update("UPDATE vehicle SET frozen = 1, frozen_shipment_id = ?, frozen_at = ? WHERE plate = ? AND frozen = 0",
                shipmentId, Timestamp.valueOf(now), plate);
    }

    // ---------- 在途占用 ----------

    /** 占用车辆：并发下主键冲突抛 {@link DuplicateKeyException}。 */
    public void occupyVehicle(String plate, long shipmentId) {
        jdbc.update("INSERT INTO vehicle_active_shipment (vehicle_plate, shipment_id) VALUES (?, ?)",
                plate, shipmentId);
    }

    public Optional<Long> findActiveShipment(String plate) {
        return jdbc.query("SELECT shipment_id FROM vehicle_active_shipment WHERE vehicle_plate = ?",
                (rs, n) -> rs.getLong("shipment_id"), plate).stream().findFirst();
    }

    /** 释放占用（运单进入终态时）。 */
    public void releaseVehicle(String plate) {
        jdbc.update("DELETE FROM vehicle_active_shipment WHERE vehicle_plate = ?", plate);
    }

    // ---------- 幂等 ----------

    private static final RowMapper<IdempotentRecord> IDEMPOTENT_MAPPER = (rs, n) ->
            new IdempotentRecord(rs.getString("request_id"), rs.getString("fingerprint"),
                    rs.getString("status"), (Integer) rs.getObject("http_status"),
                    rs.getString("response_body"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    public Optional<IdempotentRecord> findIdempotent(String requestId) {
        return jdbc.query("SELECT request_id, fingerprint, status, http_status, response_body, created_at FROM idempotent_record WHERE request_id = ?",
                IDEMPOTENT_MAPPER, requestId).stream().findFirst();
    }

    /** 插入处理中占位；并发同键返回 false。 */
    public boolean insertProcessingIdempotent(String requestId, String fingerprint, LocalDateTime now) {
        try {
            int rows = jdbc.update("INSERT INTO idempotent_record (request_id, fingerprint, status, http_status, response_body, created_at) VALUES (?, ?, 'PROCESSING', NULL, NULL, ?)",
                    requestId, fingerprint, Timestamp.valueOf(now));
            return rows == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void completeIdempotent(String requestId, int httpStatus, String responseBody) {
        jdbc.update("UPDATE idempotent_record SET status = 'COMPLETED', http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }

    /** 删除幂等占位（失败不占键语义：业务失败时释放本请求占用的键，允许改参或原参重试）。 */
    public void deleteIdempotent(String requestId) {
        jdbc.update("DELETE FROM idempotent_record WHERE request_id = ?", requestId);
    }

    // ---------- 运单 ----------

    private static final RowMapper<Shipment> SHIPMENT_MAPPER = (rs, n) -> new Shipment(
            rs.getLong("id"), rs.getString("shipment_no"), rs.getString("vehicle_plate"),
            rs.getString("goods_category"), ShipmentStatus.valueOf(rs.getString("status")),
            (Long) rs.getObject("current_approval_id"),
            rs.getTimestamp("planned_departure").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    public long insertShipment(String shipmentNo, String vehiclePlate, String goodsCategory,
                               ShipmentStatus status, Long approvalId, LocalDateTime plannedDeparture,
                               LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO shipment (shipment_no, vehicle_plate, goods_category, status, is_terminal, current_approval_id, planned_departure, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, shipmentNo);
            ps.setString(2, vehiclePlate);
            ps.setString(3, goodsCategory);
            ps.setString(4, status.name());
            ps.setInt(5, status.isTerminal() ? 1 : 0);
            if (approvalId == null) {
                ps.setObject(6, null);
            } else {
                ps.setLong(6, approvalId);
            }
            ps.setTimestamp(7, Timestamp.valueOf(plannedDeparture));
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Shipment> findShipmentByNo(String shipmentNo) {
        return jdbc.query("SELECT id, shipment_no, vehicle_plate, goods_category, status, current_approval_id, planned_departure, created_at FROM shipment WHERE shipment_no = ?",
                SHIPMENT_MAPPER, shipmentNo).stream().findFirst();
    }

    public Optional<Shipment> findShipmentById(long shipmentId) {
        return jdbc.query("SELECT id, shipment_no, vehicle_plate, goods_category, status, current_approval_id, planned_departure, created_at FROM shipment WHERE id = ?",
                SHIPMENT_MAPPER, shipmentId).stream().findFirst();
    }

    /** 无条件状态迁移（同一事务内的常规流转）。 */
    public void updateStatus(long shipmentId, ShipmentStatus status) {
        jdbc.update("UPDATE shipment SET status = ?, is_terminal = ? WHERE id = ?",
                status.name(), status.isTerminal() ? 1 : 0, shipmentId);
    }

    /**
     * 终态化条件更新：仅当当前非终态时成功，返回 1；并发终态竞争的败者返回 0。
     * 核销与失联冻结据此形成唯一终态。
     */
    public int compareAndSetTerminal(long shipmentId, ShipmentStatus terminal) {
        return jdbc.update("UPDATE shipment SET status = ?, is_terminal = 1 WHERE id = ? AND is_terminal = 0",
                terminal.name(), shipmentId);
    }

    public void updateCurrentApproval(long shipmentId, long approvalId) {
        jdbc.update("UPDATE shipment SET current_approval_id = ? WHERE id = ?", approvalId, shipmentId);
    }

    /** 查询所有非终态运单（监控扫描用）。 */
    public List<Shipment> findActiveShipments() {
        return jdbc.query("SELECT id, shipment_no, vehicle_plate, goods_category, status, current_approval_id, planned_departure, created_at FROM shipment WHERE is_terminal = 0",
                SHIPMENT_MAPPER);
    }

    // ---------- 路线核准 ----------

    private static final RowMapper<RouteApproval> APPROVAL_MAPPER = (rs, n) -> new RouteApproval(
            rs.getLong("id"), rs.getLong("shipment_id"), rs.getString("route_code"),
            rs.getInt("version_no"), RouteApprovalStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toLocalDateTime());

    public long insertApproval(long shipmentId, String routeCode, int versionNo,
                               RouteApprovalStatus status, String rejectReason, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO route_approval (shipment_id, route_code, version_no, status, reject_reason, created_at, decided_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, shipmentId);
            ps.setString(2, routeCode);
            ps.setInt(3, versionNo);
            ps.setString(4, status.name());
            ps.setString(5, rejectReason);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setObject(7, null);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<RouteApproval> findApprovals(long shipmentId) {
        return jdbc.query("SELECT id, shipment_id, route_code, version_no, status, reject_reason, created_at, decided_at FROM route_approval WHERE shipment_id = ? ORDER BY version_no",
                APPROVAL_MAPPER, shipmentId);
    }

    public Optional<RouteApproval> findApproval(long approvalId) {
        return jdbc.query("SELECT id, shipment_id, route_code, version_no, status, reject_reason, created_at, decided_at FROM route_approval WHERE id = ?",
                APPROVAL_MAPPER, approvalId).stream().findFirst();
    }

    public int updateApprovalStatus(long approvalId, RouteApprovalStatus status, LocalDateTime decidedAt) {
        return jdbc.update("UPDATE route_approval SET status = ?, decided_at = ? WHERE id = ?",
                status.name(), Timestamp.valueOf(decidedAt), approvalId);
    }

    // ---------- 途经点 ----------

    private static final RowMapper<ApprovalWaypoint> WAYPOINT_MAPPER = (rs, n) -> new ApprovalWaypoint(
            rs.getLong("id"), rs.getLong("approval_id"), rs.getLong("shipment_id"),
            rs.getInt("seq"), rs.getString("name"),
            rs.getTimestamp("planned_time").toLocalDateTime(),
            WaypointState.valueOf(rs.getString("state")),
            rs.getTimestamp("checked_time") == null ? null : rs.getTimestamp("checked_time").toLocalDateTime());

    public void insertWaypoint(long approvalId, long shipmentId, int seq, String name,
                               LocalDateTime plannedTime, WaypointState state) {
        jdbc.update("INSERT INTO approval_waypoint (approval_id, shipment_id, seq, name, planned_time, state, checked_time) VALUES (?, ?, ?, ?, ?, ?, NULL)",
                approvalId, shipmentId, seq, name, Timestamp.valueOf(plannedTime), state.name());
    }

    public List<ApprovalWaypoint> findWaypoints(long approvalId) {
        return jdbc.query("SELECT id, approval_id, shipment_id, seq, name, planned_time, state, checked_time FROM approval_waypoint WHERE approval_id = ? ORDER BY seq",
                WAYPOINT_MAPPER, approvalId);
    }

    /**
     * 途经点状态条件迁移：仅当当前状态属于期望前置集合时成功，返回受影响行数。
     * 用于超时标记（仅 PENDING）与打卡（仅 PENDING/TIMED_OUT）互斥。
     */
    public int compareAndSetWaypointState(long approvalId, int seq, WaypointState expected,
                                          WaypointState target, LocalDateTime checkedTime) {
        if (checkedTime == null) {
            return jdbc.update("UPDATE approval_waypoint SET state = ? WHERE approval_id = ? AND seq = ? AND state = ?",
                    target.name(), approvalId, seq, expected.name());
        }
        return jdbc.update("UPDATE approval_waypoint SET state = ?, checked_time = ? WHERE approval_id = ? AND seq = ? AND state = ?",
                target.name(), Timestamp.valueOf(checkedTime), approvalId, seq, expected.name());
    }

    // ---------- 打卡 ----------

    private static final RowMapper<Checkin> CHECKIN_MAPPER = (rs, n) -> new Checkin(
            rs.getLong("id"), rs.getLong("shipment_id"), rs.getLong("approval_id"),
            rs.getInt("seq"), rs.getTimestamp("check_time").toLocalDateTime(),
            rs.getString("request_id"), rs.getTimestamp("created_at").toLocalDateTime());

    public Optional<Checkin> findCheckin(long shipmentId, long approvalId, int seq) {
        return jdbc.query("SELECT id, shipment_id, approval_id, seq, check_time, request_id, created_at FROM checkin_record WHERE shipment_id = ? AND approval_id = ? AND seq = ?",
                CHECKIN_MAPPER, shipmentId, approvalId, seq).stream().findFirst();
    }

    public void insertCheckin(long shipmentId, long approvalId, int seq,
                              LocalDateTime checkTime, String requestId, LocalDateTime now) {
        jdbc.update("INSERT INTO checkin_record (shipment_id, approval_id, seq, check_time, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                shipmentId, approvalId, seq, Timestamp.valueOf(checkTime), requestId, Timestamp.valueOf(now));
    }

    public List<Checkin> findCheckinsByApproval(long approvalId) {
        return jdbc.query("SELECT id, shipment_id, approval_id, seq, check_time, request_id, created_at FROM checkin_record WHERE approval_id = ? ORDER BY check_time, seq",
                CHECKIN_MAPPER, approvalId);
    }

    // ---------- 事件 ----------

    public void insertEvent(long shipmentId, String eventType, String detail, LocalDateTime eventTime) {
        jdbc.update("INSERT INTO shipment_event (shipment_id, event_type, detail, event_time) VALUES (?, ?, ?, ?)",
                shipmentId, eventType, detail, Timestamp.valueOf(eventTime));
    }

    private static final RowMapper<ShipmentEvent> EVENT_MAPPER = (rs, n) -> new ShipmentEvent(
            rs.getLong("id"), rs.getLong("shipment_id"), rs.getString("event_type"),
            rs.getString("detail"), rs.getTimestamp("event_time").toLocalDateTime());

    public List<ShipmentEvent> findEvents(long shipmentId) {
        return jdbc.query("SELECT id, shipment_id, event_type, detail, event_time FROM shipment_event WHERE shipment_id = ? ORDER BY id",
                EVENT_MAPPER, shipmentId);
    }

    // ---------- 夜间禁行规则 ----------

    private static final RowMapper<NightCurfew> CURFEW_MAPPER = (rs, n) -> new NightCurfew(
            rs.getLong("id"), rs.getString("route_code"),
            rs.getTimestamp("curfew_start").toLocalDateTime(),
            rs.getTimestamp("curfew_end").toLocalDateTime());

    public List<NightCurfew> findNightCurfews(String routeCode) {
        return jdbc.query("SELECT id, route_code, curfew_start, curfew_end FROM night_curfew WHERE route_code = ? ORDER BY curfew_start",
                CURFEW_MAPPER, routeCode);
    }

    // ---------- 运单行锁 ----------

    /** 锁定运单行（FOR UPDATE）：串行化换班完成与禁行冻结的并发终态竞争。 */
    public void lockShipment(long shipmentId) {
        jdbc.query("SELECT id FROM shipment WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getLong("id"), shipmentId);
    }

    // ---------- 接力段 ----------

    private static final RowMapper<RelaySegment> SEGMENT_MAPPER = (rs, n) -> new RelaySegment(
            rs.getLong("id"), rs.getLong("shipment_id"), rs.getLong("approval_id"),
            rs.getInt("relay_seq"), RelaySegmentStatus.valueOf(rs.getString("status")),
            rs.getString("out_escort_id"),
            rs.getTimestamp("out_time") == null ? null : rs.getTimestamp("out_time").toLocalDateTime(),
            rs.getString("in_escort_id"),
            rs.getTimestamp("in_time") == null ? null : rs.getTimestamp("in_time").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
            rs.getInt("escort_control") == 1);

    private static final String SEGMENT_COLUMNS =
            "id, shipment_id, approval_id, relay_seq, status, out_escort_id, out_time, in_escort_id, in_time, created_at, completed_at, escort_control";

    public long insertRelaySegment(long shipmentId, long approvalId, int relaySeq,
                                   boolean escortControl, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO relay_segment (shipment_id, approval_id, relay_seq, status, out_escort_id, out_time, in_escort_id, in_time, created_at, completed_at, escort_control) "
                            + "VALUES (?, ?, ?, 'IN_PROGRESS', NULL, NULL, NULL, NULL, ?, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, shipmentId);
            ps.setLong(2, approvalId);
            ps.setInt(3, relaySeq);
            ps.setTimestamp(4, Timestamp.valueOf(now));
            ps.setInt(5, escortControl ? 1 : 0);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RelaySegment> findSegment(long shipmentId, long approvalId, int relaySeq) {
        return jdbc.query("SELECT " + SEGMENT_COLUMNS + " FROM relay_segment WHERE shipment_id = ? AND approval_id = ? AND relay_seq = ?",
                SEGMENT_MAPPER, shipmentId, approvalId, relaySeq).stream().findFirst();
    }

    public Optional<RelaySegment> findSegmentById(long segmentId) {
        return jdbc.query("SELECT " + SEGMENT_COLUMNS + " FROM relay_segment WHERE id = ?",
                SEGMENT_MAPPER, segmentId).stream().findFirst();
    }

    public List<RelaySegment> findSegments(long shipmentId) {
        return jdbc.query("SELECT " + SEGMENT_COLUMNS + " FROM relay_segment WHERE shipment_id = ? ORDER BY id",
                SEGMENT_MAPPER, shipmentId);
    }

    /** 当前进行中接力段（经占用表定位，同一运单同时刻至多一个）。 */
    public Optional<RelaySegment> findActiveSegment(long shipmentId) {
        return jdbc.query("SELECT s." + SEGMENT_COLUMNS.replace(", ", ", s.") + " FROM relay_segment s "
                        + "JOIN shipment_active_relay a ON a.segment_id = s.id WHERE a.shipment_id = ?",
                SEGMENT_MAPPER, shipmentId).stream().findFirst();
    }

    /** 换班完成条件更新：仅进行中接力段可完成，返回受影响行数（并发败者为 0）。 */
    public int completeSegment(long segmentId, String outEscortId, LocalDateTime outTime,
                               String inEscortId, LocalDateTime inTime, LocalDateTime completedAt) {
        return jdbc.update("UPDATE relay_segment SET status = 'COMPLETED', out_escort_id = ?, out_time = ?, "
                        + "in_escort_id = ?, in_time = ?, completed_at = ? WHERE id = ? AND status = 'IN_PROGRESS'",
                outEscortId, Timestamp.valueOf(outTime), inEscortId, Timestamp.valueOf(inTime),
                Timestamp.valueOf(completedAt), segmentId);
    }

    /** 禁行冻结：将该运单进行中接力段置为 FROZEN，返回受影响行数。 */
    public int freezeActiveSegments(long shipmentId) {
        return jdbc.update("UPDATE relay_segment SET status = 'FROZEN' WHERE shipment_id = ? AND status = 'IN_PROGRESS'",
                shipmentId);
    }

    /** 占用进行中接力段：并发下同运单主键冲突抛 {@link DuplicateKeyException}。 */
    public void occupyRelay(long shipmentId, long segmentId) {
        jdbc.update("INSERT INTO shipment_active_relay (shipment_id, segment_id) VALUES (?, ?)",
                shipmentId, segmentId);
    }

    /** 释放进行中接力段占用（换班完成或禁行冻结时）。 */
    public void releaseRelay(long shipmentId) {
        jdbc.update("DELETE FROM shipment_active_relay WHERE shipment_id = ?", shipmentId);
    }

    // ---------- 接力双人打卡 ----------

    private static final RowMapper<RelayCheckin> RELAY_CHECKIN_MAPPER = (rs, n) -> new RelayCheckin(
            rs.getLong("id"), rs.getLong("segment_id"), rs.getLong("shipment_id"),
            rs.getString("escort_id"), EscortRole.valueOf(rs.getString("role")),
            rs.getTimestamp("check_time").toLocalDateTime(),
            rs.getString("request_id"), rs.getTimestamp("created_at").toLocalDateTime());

    private static final String RELAY_CHECKIN_COLUMNS =
            "id, segment_id, shipment_id, escort_id, role, check_time, request_id, created_at";

    public Optional<RelayCheckin> findRelayCheckin(long segmentId, EscortRole role) {
        return jdbc.query("SELECT " + RELAY_CHECKIN_COLUMNS + " FROM relay_checkin WHERE segment_id = ? AND role = ?",
                RELAY_CHECKIN_MAPPER, segmentId, role.name()).stream().findFirst();
    }

    public List<RelayCheckin> findRelayCheckins(long segmentId) {
        return jdbc.query("SELECT " + RELAY_CHECKIN_COLUMNS + " FROM relay_checkin WHERE segment_id = ? ORDER BY check_time, id",
                RELAY_CHECKIN_MAPPER, segmentId);
    }

    public void insertRelayCheckin(long segmentId, long shipmentId, String escortId, EscortRole role,
                                   LocalDateTime checkTime, String requestId, LocalDateTime now) {
        jdbc.update("INSERT INTO relay_checkin (segment_id, shipment_id, escort_id, role, check_time, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                segmentId, shipmentId, escortId, role.name(), Timestamp.valueOf(checkTime),
                requestId, Timestamp.valueOf(now));
    }

    // ---------- 处置登记 ----------

    public void insertDisposal(long shipmentId, Long segmentId, String reason,
                               LocalDateTime curfewStart, LocalDateTime now) {
        jdbc.update("INSERT INTO disposal_record (shipment_id, segment_id, reason, curfew_start, created_at) VALUES (?, ?, ?, ?, ?)",
                shipmentId, segmentId, reason, Timestamp.valueOf(curfewStart), Timestamp.valueOf(now));
    }

    private static final RowMapper<DisposalRecord> DISPOSAL_MAPPER = (rs, n) -> new DisposalRecord(
            rs.getLong("id"), rs.getLong("shipment_id"), (Long) rs.getObject("segment_id"),
            rs.getString("reason"), rs.getTimestamp("curfew_start").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    public List<DisposalRecord> findDisposals(long shipmentId) {
        return jdbc.query("SELECT id, shipment_id, segment_id, reason, curfew_start, created_at FROM disposal_record WHERE shipment_id = ? ORDER BY id",
                DISPOSAL_MAPPER, shipmentId);
    }

    // ---------- 接班员合成资格 ----------

    private static final RowMapper<EscortQualification> QUALIFICATION_MAPPER = (rs, n) ->
            new EscortQualification(rs.getLong("id"), rs.getString("escort_id"),
                    rs.getString("goods_category"),
                    rs.getTimestamp("valid_from").toLocalDateTime(),
                    rs.getTimestamp("valid_to").toLocalDateTime(),
                    rs.getInt("revoked") == 1, rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toLocalDateTime());

    private static final String QUALIFICATION_COLUMNS =
            "id, escort_id, goods_category, valid_from, valid_to, revoked, version_no, created_at, revoked_at";

    public long insertQualification(String escortId, String goodsCategory, LocalDateTime validFrom,
                                    LocalDateTime validTo, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO escort_qualification (escort_id, goods_category, valid_from, valid_to, revoked, version_no, created_at, revoked_at) "
                            + "VALUES (?, ?, ?, ?, 0, 1, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, escortId);
            ps.setString(2, goodsCategory);
            ps.setTimestamp(3, Timestamp.valueOf(validFrom));
            ps.setTimestamp(4, Timestamp.valueOf(validTo));
            ps.setTimestamp(5, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<EscortQualification> findQualification(long id) {
        return jdbc.query("SELECT " + QUALIFICATION_COLUMNS + " FROM escort_qualification WHERE id = ?",
                QUALIFICATION_MAPPER, id).stream().findFirst();
    }

    /** 该接班员指定货类的未撤销资格（取最新创建的一条）；无则 empty。 */
    public Optional<EscortQualification> findActiveQualification(String escortId, String goodsCategory) {
        return jdbc.query("SELECT " + QUALIFICATION_COLUMNS + " FROM escort_qualification "
                        + "WHERE escort_id = ? AND goods_category = ? AND revoked = 0 ORDER BY id DESC",
                QUALIFICATION_MAPPER, escortId, goodsCategory).stream().findFirst();
    }

    /** 该接班员全部资格（含已撤销），用于区分货类不符与资格已撤销。 */
    public List<EscortQualification> findQualificationsByEscort(String escortId) {
        return jdbc.query("SELECT " + QUALIFICATION_COLUMNS + " FROM escort_qualification "
                        + "WHERE escort_id = ? ORDER BY id",
                QUALIFICATION_MAPPER, escortId);
    }

    /**
     * 撤销资格（乐观版本 CAS）：仅当版本匹配且未撤销时成功，版本号加 1，返回受影响行数。
     * 过期版本或已撤销返回 0（409）。
     */
    public int revokeQualification(long id, int expectedVersion, LocalDateTime now) {
        return jdbc.update("UPDATE escort_qualification SET revoked = 1, revoked_at = ?, version_no = version_no + 1 "
                        + "WHERE id = ? AND version_no = ? AND revoked = 0",
                Timestamp.valueOf(now), id, expectedVersion);
    }

    /** 该接班员作为 IN 方已完成换班的运单 ID 列表（撤销资格时留风险证据用）。 */
    public List<Long> findCompletedHandoverShipmentIds(String escortId) {
        return jdbc.query("SELECT DISTINCT shipment_id FROM relay_segment WHERE in_escort_id = ? AND status = 'COMPLETED'",
                (rs, n) -> rs.getLong("shipment_id"), escortId);
    }

    // ---------- 人员预约行锁 ----------

    /** 锁定接班员行（FOR UPDATE）：预约、资格撤销、受控段 IN 确认据此串行化，形成一致次序。 */
    public void lockEscort(String escortId) {
        jdbc.update("MERGE INTO escort_booking_lock (escort_id) KEY (escort_id) VALUES (?)", escortId);
        jdbc.query("SELECT escort_id FROM escort_booking_lock WHERE escort_id = ? FOR UPDATE",
                (rs, n) -> rs.getString("escort_id"), escortId);
    }

    // ---------- 人员资格预约 ----------

    private static final RowMapper<EscortReservation> RESERVATION_MAPPER = (rs, n) ->
            new EscortReservation(rs.getLong("id"), rs.getLong("shipment_id"), rs.getLong("segment_id"),
                    rs.getString("escort_id"), rs.getLong("qualification_id"),
                    rs.getTimestamp("shift_start").toLocalDateTime(),
                    rs.getTimestamp("shift_end").toLocalDateTime(),
                    rs.getString("status"), rs.getInt("version_no"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toLocalDateTime());

    private static final String RESERVATION_COLUMNS =
            "id, shipment_id, segment_id, escort_id, qualification_id, shift_start, shift_end, status, version_no, created_at, released_at";

    public long insertReservation(long shipmentId, long segmentId, String escortId, long qualificationId,
                                  LocalDateTime shiftStart, LocalDateTime shiftEnd, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO escort_reservation (shipment_id, segment_id, escort_id, qualification_id, shift_start, shift_end, status, version_no, created_at, released_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, shipmentId);
            ps.setLong(2, segmentId);
            ps.setString(3, escortId);
            ps.setLong(4, qualificationId);
            ps.setTimestamp(5, Timestamp.valueOf(shiftStart));
            ps.setTimestamp(6, Timestamp.valueOf(shiftEnd));
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<EscortReservation> findReservation(long id) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM escort_reservation WHERE id = ?",
                RESERVATION_MAPPER, id).stream().findFirst();
    }

    /** 该接力段的生效中预约（受控段 IN 确认依据）。 */
    public Optional<EscortReservation> findActiveReservationBySegment(long segmentId) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM escort_reservation "
                        + "WHERE segment_id = ? AND status = 'ACTIVE' ORDER BY id DESC",
                RESERVATION_MAPPER, segmentId).stream().findFirst();
    }

    /** 该接班员全部生效中预约（班段重叠判断；须在持有人员行锁后调用）。 */
    public List<EscortReservation> findActiveReservationsByEscort(String escortId) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM escort_reservation "
                        + "WHERE escort_id = ? AND status = 'ACTIVE' ORDER BY shift_start",
                RESERVATION_MAPPER, escortId);
    }

    public List<EscortReservation> findReservationsByShipment(long shipmentId) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " FROM escort_reservation "
                        + "WHERE shipment_id = ? ORDER BY id",
                RESERVATION_MAPPER, shipmentId);
    }

    /**
     * 撤销预约（乐观版本 CAS）：仅当版本匹配且生效中时成功，版本号加 1，返回受影响行数。
     * 过期版本或已释放返回 0（409）。
     */
    public int releaseReservation(long id, int expectedVersion, LocalDateTime now) {
        return jdbc.update("UPDATE escort_reservation SET status = 'RELEASED', released_at = ?, version_no = version_no + 1 "
                        + "WHERE id = ? AND version_no = ? AND status = 'ACTIVE'",
                Timestamp.valueOf(now), id, expectedVersion);
    }

    /** 运单终结释放未结束预约：该运单生效中且对应接力段未完成换班的预约置为 RELEASED。 */
    public int releaseUnfinishedReservations(long shipmentId, LocalDateTime now) {
        return jdbc.update("UPDATE escort_reservation SET status = 'RELEASED', released_at = ?, version_no = version_no + 1 "
                        + "WHERE shipment_id = ? AND status = 'ACTIVE' "
                        + "AND segment_id IN (SELECT id FROM relay_segment WHERE shipment_id = ? AND status <> 'COMPLETED')",
                Timestamp.valueOf(now), shipmentId, shipmentId);
    }

    /** 资格撤销连带释放：该资格下生效中且对应接力段尚未任何打卡的预约置为 RELEASED。 */
    public int releaseReleasableByQualification(long qualificationId, LocalDateTime now) {
        return jdbc.update("UPDATE escort_reservation SET status = 'RELEASED', released_at = ?, version_no = version_no + 1 "
                        + "WHERE qualification_id = ? AND status = 'ACTIVE' "
                        + "AND NOT EXISTS (SELECT 1 FROM relay_checkin c WHERE c.segment_id = escort_reservation.segment_id)",
                Timestamp.valueOf(now), qualificationId);
    }
}
