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
import com.example.starter.hazmat.domain.RelayStatus;
import com.example.starter.hazmat.domain.RouteApprovalStatus;
import com.example.starter.hazmat.domain.ShipmentStatus;
import com.example.starter.hazmat.domain.WaypointState;
import com.example.starter.hazmat.po.ApprovalWaypoint;
import com.example.starter.hazmat.po.Checkin;
import com.example.starter.hazmat.po.ControlPeriod;
import com.example.starter.hazmat.po.CurfewPeriod;
import com.example.starter.hazmat.po.DisposalRecord;
import com.example.starter.hazmat.po.GoodsCategory;
import com.example.starter.hazmat.po.IdempotentRecord;
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

    /**
     * 守卫更新：仅当运单非终态时返回 1。更新本身持有行锁至事务结束，
     * 用于接力换班完成与禁行冻结/失联终态 CAS 在同一运单行上互斥序列化。
     */
    public int touchIfNotTerminal(long shipmentId) {
        return jdbc.update("UPDATE shipment SET status = status WHERE id = ? AND is_terminal = 0", shipmentId);
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

    // ---------- 夜间禁行时段 ----------

    private static final RowMapper<CurfewPeriod> CURFEW_MAPPER = (rs, n) ->
            new CurfewPeriod(rs.getLong("id"),
                    rs.getTimestamp("start_time").toLocalDateTime(),
                    rs.getTimestamp("end_time").toLocalDateTime());

    public List<CurfewPeriod> findCurfews() {
        return jdbc.query("SELECT id, start_time, end_time FROM curfew_period ORDER BY start_time",
                CURFEW_MAPPER);
    }

    // ---------- 押运接力段 ----------

    private static final RowMapper<RelaySegment> RELAY_MAPPER = (rs, n) -> new RelaySegment(
            rs.getLong("id"), rs.getLong("shipment_id"), rs.getLong("approval_id"),
            rs.getInt("waypoint_seq"),
            rs.getTimestamp("curfew_start").toLocalDateTime(),
            RelayStatus.valueOf(rs.getString("status")),
            rs.getString("outgoing_escort"), rs.getString("incoming_escort"),
            rs.getTimestamp("outgoing_time") == null ? null : rs.getTimestamp("outgoing_time").toLocalDateTime(),
            rs.getTimestamp("incoming_time") == null ? null : rs.getTimestamp("incoming_time").toLocalDateTime(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final String RELAY_COLUMNS = "id, shipment_id, approval_id, waypoint_seq, curfew_start, status,"
            + " outgoing_escort, incoming_escort, outgoing_time, incoming_time, completed_at, created_at";

    public long insertRelaySegment(long shipmentId, long approvalId, int waypointSeq,
                                   LocalDateTime curfewStart, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO relay_segment (shipment_id, approval_id, waypoint_seq, curfew_start, status,"
                            + " outgoing_escort, incoming_escort, outgoing_time, incoming_time, completed_at, created_at) "
                            + "VALUES (?, ?, ?, ?, 'OPEN', NULL, NULL, NULL, NULL, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, shipmentId);
            ps.setLong(2, approvalId);
            ps.setInt(3, waypointSeq);
            ps.setTimestamp(4, Timestamp.valueOf(curfewStart));
            ps.setTimestamp(5, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RelaySegment> findRelaySegment(long relayId) {
        return jdbc.query("SELECT " + RELAY_COLUMNS + " FROM relay_segment WHERE id = ?",
                RELAY_MAPPER, relayId).stream().findFirst();
    }

    /** 运单当前进行中接力段（经占用表定位，同时刻至多一个）。 */
    public Optional<RelaySegment> findOpenRelay(long shipmentId) {
        return jdbc.query("SELECT r.id, r.shipment_id, r.approval_id, r.waypoint_seq, r.curfew_start, r.status,"
                        + " r.outgoing_escort, r.incoming_escort, r.outgoing_time, r.incoming_time,"
                        + " r.completed_at, r.created_at FROM relay_segment r "
                        + "JOIN shipment_active_relay a ON a.relay_id = r.id WHERE a.shipment_id = ?",
                RELAY_MAPPER, shipmentId).stream().findFirst();
    }

    /** 运单最近一次接力段（视图展示用）。 */
    public Optional<RelaySegment> findLatestRelay(long shipmentId) {
        return jdbc.query("SELECT " + RELAY_COLUMNS + " FROM relay_segment WHERE shipment_id = ? ORDER BY id DESC",
                RELAY_MAPPER, shipmentId).stream().findFirst();
    }

    /** 换班完成：记录双方编号与时刻并置 COMPLETED。 */
    public void completeRelaySegment(long relayId, String outgoingEscort, String incomingEscort,
                                     LocalDateTime outgoingTime, LocalDateTime incomingTime,
                                     LocalDateTime completedAt) {
        jdbc.update("UPDATE relay_segment SET status = 'COMPLETED', outgoing_escort = ?, incoming_escort = ?,"
                        + " outgoing_time = ?, incoming_time = ?, completed_at = ? WHERE id = ?",
                outgoingEscort, incomingEscort, Timestamp.valueOf(outgoingTime),
                Timestamp.valueOf(incomingTime), Timestamp.valueOf(completedAt), relayId);
    }

    /** 违约冻结：置 BREACHED。 */
    public void breachRelaySegment(long relayId) {
        jdbc.update("UPDATE relay_segment SET status = 'BREACHED' WHERE id = ?", relayId);
    }

    /** 占用进行中接力段：并发下同运单主键冲突抛 {@link DuplicateKeyException}。 */
    public void occupyRelay(long shipmentId, long relayId) {
        jdbc.update("INSERT INTO shipment_active_relay (shipment_id, relay_id) VALUES (?, ?)",
                shipmentId, relayId);
    }

    /** 释放进行中接力段占用（换班完成或违约冻结时）。 */
    public void releaseRelay(long shipmentId) {
        jdbc.update("DELETE FROM shipment_active_relay WHERE shipment_id = ?", shipmentId);
    }

    // ---------- 接力段打卡 ----------

    private static final RowMapper<RelayCheckin> RELAY_CHECKIN_MAPPER = (rs, n) -> new RelayCheckin(
            rs.getLong("id"), rs.getLong("relay_id"), rs.getLong("shipment_id"),
            rs.getInt("waypoint_seq"), EscortRole.valueOf(rs.getString("escort_role")),
            rs.getString("escort_id"), rs.getTimestamp("check_time").toLocalDateTime(),
            rs.getString("request_id"), rs.getTimestamp("created_at").toLocalDateTime());

    public Optional<RelayCheckin> findRelayCheckin(long shipmentId, int waypointSeq, EscortRole role) {
        return jdbc.query("SELECT id, relay_id, shipment_id, waypoint_seq, escort_role, escort_id, check_time, request_id, created_at "
                        + "FROM relay_checkin WHERE shipment_id = ? AND waypoint_seq = ? AND escort_role = ?",
                RELAY_CHECKIN_MAPPER, shipmentId, waypointSeq, role.name()).stream().findFirst();
    }

    public List<RelayCheckin> findRelayCheckins(long relayId) {
        return jdbc.query("SELECT id, relay_id, shipment_id, waypoint_seq, escort_role, escort_id, check_time, request_id, created_at "
                        + "FROM relay_checkin WHERE relay_id = ? ORDER BY check_time, id",
                RELAY_CHECKIN_MAPPER, relayId);
    }

    public void insertRelayCheckin(long relayId, long shipmentId, int waypointSeq, EscortRole role,
                                   String escortId, LocalDateTime checkTime, String requestId,
                                   LocalDateTime now) {
        jdbc.update("INSERT INTO relay_checkin (relay_id, shipment_id, waypoint_seq, escort_role, escort_id, check_time, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                relayId, shipmentId, waypointSeq, role.name(), escortId,
                Timestamp.valueOf(checkTime), requestId, Timestamp.valueOf(now));
    }

    // ---------- 处置登记 ----------

    public void insertDisposal(long shipmentId, Long relayId, String reason, LocalDateTime now) {
        jdbc.update("INSERT INTO disposal_record (shipment_id, relay_id, reason, registered_at) VALUES (?, ?, ?, ?)",
                shipmentId, relayId, reason, Timestamp.valueOf(now));
    }

    private static final RowMapper<DisposalRecord> DISPOSAL_MAPPER = (rs, n) -> new DisposalRecord(
            rs.getLong("id"), rs.getLong("shipment_id"), (Long) rs.getObject("relay_id"),
            rs.getString("reason"), rs.getTimestamp("registered_at").toLocalDateTime());

    public List<DisposalRecord> findDisposals(long shipmentId) {
        return jdbc.query("SELECT id, shipment_id, relay_id, reason, registered_at FROM disposal_record WHERE shipment_id = ? ORDER BY id",
                DISPOSAL_MAPPER, shipmentId);
    }
}
