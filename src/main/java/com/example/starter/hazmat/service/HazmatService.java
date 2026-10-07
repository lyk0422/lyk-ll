package com.example.starter.hazmat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.clock.MutableClock;
import com.example.starter.hazmat.domain.EscortRole;
import com.example.starter.hazmat.domain.RelaySegmentStatus;
import com.example.starter.hazmat.domain.ReservationStatus;
import com.example.starter.hazmat.domain.RouteApprovalStatus;
import com.example.starter.hazmat.domain.ShipmentStatus;
import com.example.starter.hazmat.domain.WaypointState;
import com.example.starter.hazmat.dto.ApplyRequest;
import com.example.starter.hazmat.dto.CheckinRequest;
import com.example.starter.hazmat.dto.QualificationCreateRequest;
import com.example.starter.hazmat.dto.QualificationRevokeRequest;
import com.example.starter.hazmat.dto.RelayCheckinRequest;
import com.example.starter.hazmat.dto.RelayPointRequest;
import com.example.starter.hazmat.dto.RerouteRequest;
import com.example.starter.hazmat.dto.ReservationCreateRequest;
import com.example.starter.hazmat.dto.ReservationRevokeRequest;
import com.example.starter.hazmat.po.ApprovalWaypoint;
import com.example.starter.hazmat.po.Checkin;
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
import com.example.starter.hazmat.repo.HazmatRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 危化品运输路线管制与押运打卡核心服务。
 *
 * <p>事务边界：写操作通过 {@link TransactionTemplate} 显式包裹；幂等占位独立于业务事务提交，
 * 业务失败（含逆序打卡拒绝）也记录首次结果用于同键重放；逆序告警使用独立事务保证回滚不丢告警。
 */
@Service
public class HazmatService {

    /** 事件类型。 */
    public static final String EVT_ROUTE_REJECTED = "ROUTE_REJECTED";
    public static final String EVT_REROUTE_REJECTED = "REROUTE_REJECTED";
    public static final String EVT_REROUTE_SWITCH = "REROUTE_SWITCH";
    public static final String EVT_CHECKIN_REVERSED = "CHECKIN_REVERSED";
    public static final String EVT_DEVIATION_WARNING = "DEVIATION_WARNING";
    public static final String EVT_LOST_CONTACT = "LOST_CONTACT";
    public static final String EVT_LATE_CHECKIN_AFTER_CLOSE = "LATE_CHECKIN_AFTER_CLOSE";
    public static final String EVT_RELAY_POINT_SET = "RELAY_POINT_SET";
    public static final String EVT_RELAY_HANDOVER = "RELAY_HANDOVER";
    public static final String EVT_RELAY_CHECKIN_REVERSED = "RELAY_CHECKIN_REVERSED";
    public static final String EVT_CURFEW_FROZEN = "CURFEW_FROZEN";
    public static final String EVT_RESERVATION_CREATED = "RESERVATION_CREATED";
    public static final String EVT_RESERVATION_RELEASED = "RESERVATION_RELEASED";
    public static final String EVT_QUALIFICATION_REVOKED_RISK = "QUALIFICATION_REVOKED_RISK";

    /** 连续超时途经点数达到该值即升级失联（恰 2 个触发）。 */
    static final int LOST_CONTACT_CONSECUTIVE_TIMEOUTS = 2;

    private final HazmatRepository repository;
    private final EventRecorder eventRecorder;
    private final MutableClock clock;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public HazmatService(HazmatRepository repository, EventRecorder eventRecorder,
                         MutableClock clock, PlatformTransactionManager transactionManager,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.eventRecorder = eventRecorder;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    private LocalDateTime now() {
        return clock.now();
    }

    // ============================ 运输申请 ============================

    public ServiceResult apply(ApplyRequest request) {
        String fingerprint = fingerprint("APPLY",
                request.requestId(), request.vehiclePlate(), request.goodsCategory(),
                request.routeCode(), request.plannedDeparture().toString());
        return withIdempotency(request.requestId(), fingerprint,
                () -> tx.execute(status -> doApply(request)));
    }

    private ServiceResult doApply(ApplyRequest request) {
        LocalDateTime now = now();
        GoodsCategory category = repository.findCategory(request.goodsCategory())
                .orElseThrow(() -> ApiException.badRequest("CATEGORY_NOT_FOUND",
                        "危化品类别不存在: " + request.goodsCategory()));
        RouteInfo route = repository.findRoute(request.routeCode())
                .orElseThrow(() -> ApiException.badRequest("ROUTE_NOT_FOUND",
                        "路线不存在: " + request.routeCode()));
        var vehicle = repository.findVehicle(request.vehiclePlate())
                .orElseThrow(() -> ApiException.badRequest("VEHICLE_NOT_FOUND",
                        "车辆不存在: " + request.vehiclePlate()));
        if (vehicle.frozen()) {
            throw ApiException.conflict("VEHICLE_FROZEN",
                    "车辆 " + request.vehiclePlate() + " 已因失联冻结，禁止新申请");
        }
        if (repository.findActiveShipment(request.vehiclePlate()).isPresent()) {
            throw ApiException.conflict("VEHICLE_BUSY",
                    "车辆 " + request.vehiclePlate() + " 同时刻只允许一个执行中运单");
        }
        List<RouteWaypointDef> defs = repository.findWaypointDefs(request.routeCode());
        if (defs.isEmpty()) {
            throw ApiException.badRequest("ROUTE_WAYPOINTS_MISSING",
                    "路线 " + request.routeCode() + " 未定义途经点");
        }

        String shipmentNo = "HZ" + request.plannedDeparture().toLocalDate().toString().replace("-", "")
                + "-" + Integer.toHexString(request.requestId().hashCode() & 0x7fffffff).toUpperCase();
        long shipmentId = repository.insertShipment(shipmentNo, request.vehiclePlate(),
                request.goodsCategory(), ShipmentStatus.APPLIED, null,
                request.plannedDeparture(), now);
        try {
            // 主键冲突即并发占用失败：回滚运单，返回 409。
            repository.occupyVehicle(request.vehiclePlate(), shipmentId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("VEHICLE_BUSY",
                    "车辆 " + request.vehiclePlate() + " 同时刻只允许一个执行中运单");
        }
        long approvalId = repository.insertApproval(shipmentId, request.routeCode(), 1,
                RouteApprovalStatus.PENDING, null, now);
        for (RouteWaypointDef def : defs) {
            repository.insertWaypoint(approvalId, shipmentId, def.seq(), def.name(),
                    request.plannedDeparture().plusMinutes(def.offsetMinutes()), WaypointState.PENDING);
        }
        repository.updateCurrentApproval(shipmentId, approvalId);

        Map<String, Object> body = baseShipmentView(repository.findShipmentById(shipmentId).orElseThrow());
        body.put("message", "申请已受理，等待路线核准");
        return ServiceResult.ok(body);
    }

    // ============================ 路线核准 ============================

    public ServiceResult decideApproval(String shipmentNo, boolean approved) {
        return tx.execute(status -> doDecide(shipmentNo, approved));
    }

    private ServiceResult doDecide(String shipmentNo, boolean approved) {
        Shipment shipment = loadShipment(shipmentNo);
        if (shipment.status() != ShipmentStatus.APPLIED) {
            throw ApiException.conflict("INVALID_STATUS",
                    "运单当前状态 " + shipment.status() + "，不可路线核准");
        }
        RouteApproval approval = currentApproval(shipment);
        if (approval.status() != RouteApprovalStatus.PENDING || approval.versionNo() != 1) {
            throw ApiException.conflict("APPROVAL_NOT_PENDING", "首版路线核准记录状态异常");
        }
        LocalDateTime now = now();

        RouteRuleEvaluator.Result result = evaluate(approval.routeCode(),
                shipment.goodsCategory(), shipment.plannedDeparture());
        if (!approved || !result.allowed()) {
            // 驳回事务必须落库（终态 + 释放车辆占用），故提交后返回 422，而非抛异常回滚。
            String reason = approved ? result.message() : "人工驳回";
            repository.updateApprovalStatus(approval.id(), RouteApprovalStatus.REJECTED, now);
            repository.insertEvent(shipment.id(), EVT_ROUTE_REJECTED, reason, now);
            repository.updateStatus(shipment.id(), ShipmentStatus.ROUTE_REJECTED);
            repository.releaseVehicle(shipment.vehiclePlate());
            repository.releaseActiveReservations(shipment.id(), now);
            Map<String, Object> body = shipmentView(shipmentNo);
            body.put("errorCode", "ROUTE_REJECTED");
            body.put("message", reason);
            return ServiceResult.status(422, body);
        }
        repository.updateApprovalStatus(approval.id(), RouteApprovalStatus.APPROVED, now);
        repository.updateStatus(shipment.id(), ShipmentStatus.ROUTE_APPROVED);
        return ServiceResult.ok(shipmentView(shipmentNo));
    }

    // ============================ 发运 ============================

    public ServiceResult dispatch(String shipmentNo, String requestId) {
        String fingerprint = fingerprint("DISPATCH", requestId, shipmentNo);
        return withIdempotency(requestId, fingerprint,
                () -> tx.execute(status -> doDispatch(shipmentNo)));
    }

    private ServiceResult doDispatch(String shipmentNo) {
        Shipment shipment = loadShipment(shipmentNo);
        if (shipment.status() == ShipmentStatus.DISPATCHED || shipment.status() == ShipmentStatus.IN_TRANSIT) {
            throw ApiException.conflict("ALREADY_DISPATCHED", "运单已发运");
        }
        if (shipment.status() != ShipmentStatus.ROUTE_APPROVED) {
            throw ApiException.conflict("INVALID_STATUS",
                    "运单当前状态 " + shipment.status() + "，不可发运");
        }
        repository.updateStatus(shipment.id(), ShipmentStatus.DISPATCHED);
        return ServiceResult.ok(shipmentView(shipmentNo));
    }

    // ============================ 押运打卡 ============================

    public ServiceResult checkin(String shipmentNo, CheckinRequest request) {
        String fingerprint = fingerprint("CHECKIN", request.requestId(), shipmentNo,
                String.valueOf(request.waypointSeq()), request.checkTime().toString());
        // 逆序拒绝走异常路径：告警独立事务落库，业务事务回滚，首次失败响应同样幂等保存。
        return withIdempotency(request.requestId(), fingerprint,
                () -> tx.execute(status -> doCheckin(shipmentNo, request)));
    }

    private ServiceResult doCheckin(String shipmentNo, CheckinRequest request) {
        Shipment shipment = loadShipment(shipmentNo);
        // 行锁串行化打卡与禁行冻结/失联升级的并发终态竞争。
        repository.lockShipment(shipment.id());
        shipment = repository.findShipmentById(shipment.id()).orElseThrow();
        LocalDateTime now = now();

        if (shipment.status() == ShipmentStatus.COMPLETED) {
            // 结案为终态：迟到打卡不改任何业务状态，只记录迟到事件。
            eventRecorder.recordNewTransaction(shipment.id(), EVT_LATE_CHECKIN_AFTER_CLOSE,
                    "结案后收到途经点 " + request.waypointSeq() + " 打卡，时刻 " + request.checkTime(), now);
            Map<String, Object> body = shipmentView(shipmentNo);
            body.put("lateAfterClosed", true);
            return ServiceResult.ok(body);
        }
        if (shipment.status().isTerminal()) {
            throw ApiException.conflict("SHIPMENT_TERMINAL",
                    "运单终态 " + shipment.status() + "，不可打卡");
        }
        if (shipment.status() != ShipmentStatus.DISPATCHED && shipment.status() != ShipmentStatus.IN_TRANSIT) {
            throw ApiException.conflict("NOT_DISPATCHED",
                    "运单当前状态 " + shipment.status() + "，不可打卡");
        }

        // 夜间禁行：未在禁行起点前完成换班的运单，恰满起点即在同一事务内冻结并转处置。
        if (freezeIfCurfewOverdue(shipment, now)) {
            return curfewFrozenResult(shipmentNo);
        }
        // 换班完成不豁免禁行：打卡时刻落入夜间禁行窗口即拒绝（仅合法时段或获准改线后可通行）。
        RouteApproval checkinApproval = currentApproval(shipment);
        for (NightCurfew curfew : repository.findNightCurfews(checkinApproval.routeCode())) {
            if (!request.checkTime().isBefore(curfew.curfewStart())
                    && request.checkTime().isBefore(curfew.curfewEnd())) {
                throw ApiException.conflict("NIGHT_CURFEW_PERIOD",
                        "打卡时刻 " + request.checkTime() + " 落入夜间禁行时段 [" + curfew.curfewStart()
                                + ", " + curfew.curfewEnd() + ")，禁行时段内禁止通行打卡");
            }
        }

        long approvalId = shipment.currentApprovalId();
        List<ApprovalWaypoint> waypoints = repository.findWaypoints(approvalId);
        ApprovalWaypoint target = waypoints.stream()
                .filter(w -> w.seq() == request.waypointSeq()).findFirst()
                .orElseThrow(() -> ApiException.badRequest("WAYPOINT_NOT_FOUND",
                        "当前路线无途经点序号 " + request.waypointSeq()));

        Optional<Checkin> duplicate = repository.findCheckin(shipment.id(), approvalId, target.seq());
        if (duplicate.isPresent()) {
            throw ApiException.conflict("WAYPOINT_ALREADY_CHECKED",
                    "途经点 " + target.seq() + " 已打卡（运单+途经点序号去重），首次打卡时刻 "
                            + duplicate.get().checkTime());
        }

        // 时序校验：与最近的前序、后序已打卡途经点比较；乱序回传允许（按时刻归位），时刻逆序拒绝并告警。
        Checkin prev = nearestChecked(waypoints, target.seq(), true);
        Checkin next = nearestChecked(waypoints, target.seq(), false);
        if (prev != null && !request.checkTime().isAfter(prev.checkTime())) {
            rejectReverse(shipment, target.seq(), request.checkTime(),
                    "晚于前序途经点 " + prev.seq() + " 的打卡时刻 " + prev.checkTime(), now);
        }
        if (next != null && !request.checkTime().isBefore(next.checkTime())) {
            rejectReverse(shipment, target.seq(), request.checkTime(),
                    "早于后序途经点 " + next.seq() + " 的打卡时刻 " + next.checkTime(), now);
        }

        boolean late = !request.checkTime().isBefore(target.deadline());
        WaypointState targetState = late ? WaypointState.CHECKED_LATE : WaypointState.CHECKED;
        int rows;
        if (late) {
            // 既可能从未扫描（PENDING），也可能已被标记偏离（TIMED_OUT）。
            rows = repository.compareAndSetWaypointState(approvalId, target.seq(),
                    WaypointState.PENDING, targetState, request.checkTime())
                    + repository.compareAndSetWaypointState(approvalId, target.seq(),
                    WaypointState.TIMED_OUT, targetState, request.checkTime());
        } else {
            rows = repository.compareAndSetWaypointState(approvalId, target.seq(),
                    WaypointState.PENDING, targetState, request.checkTime());
        }
        if (rows == 0) {
            // 并发：监控扫描或另一打卡刚刚改变状态。
            Optional<Checkin> raced = repository.findCheckin(shipment.id(), approvalId, target.seq());
            if (raced.isPresent()) {
                throw ApiException.conflict("WAYPOINT_ALREADY_CHECKED",
                        "途经点 " + target.seq() + " 已打卡，首次打卡时刻 " + raced.get().checkTime());
            }
            throw ApiException.conflict("WAYPOINT_STATE_CHANGED",
                    "途经点 " + target.seq() + " 状态刚被更新，请重试");
        }
        repository.insertCheckin(shipment.id(), approvalId, target.seq(),
                request.checkTime(), request.requestId(), now);
        if (shipment.status() == ShipmentStatus.DISPATCHED) {
            repository.updateStatus(shipment.id(), ShipmentStatus.IN_TRANSIT);
        }
        return ServiceResult.ok(shipmentView(shipmentNo));
    }

    private void rejectReverse(Shipment shipment, int seq, LocalDateTime checkTime, String detail, LocalDateTime now) {
        String message = "途经点 " + seq + " 打卡时刻 " + checkTime + " 未" + detail + "，逆序拒绝";
        eventRecorder.recordNewTransaction(shipment.id(), EVT_CHECKIN_REVERSED, message, now);
        throw ApiException.badRequest("CHECKIN_REVERSED", message);
    }

    /** 查找序号最近的已打卡途经点：backward=true 向前，false 向后（仅限当前生效路线版本）。 */
    private Checkin nearestChecked(List<ApprovalWaypoint> waypoints, int seq, boolean backward) {
        ApprovalWaypoint result = null;
        for (ApprovalWaypoint w : waypoints) {
            if (backward ? (w.seq() < seq) : (w.seq() > seq)) {
                if (result == null
                        || (backward && w.seq() > result.seq())
                        || (!backward && w.seq() < result.seq())) {
                    result = w;
                }
            }
        }
        if (result == null) {
            return null;
        }
        return repository.findCheckin(result.shipmentId(), result.approvalId(), result.seq()).orElse(null);
    }

    // ============================ 夜间接力押运 ============================

    /**
     * 设置安全接力点（创建进行中接力段）。
     * 约束：途经点计划通过时刻须严格早于夜间禁行起点；运单+途经点序号去重；
     * 同一运单同时刻只允许一个进行中接力段（占用表主键冲突即 409）。
     */
    public ServiceResult setRelayPoint(String shipmentNo, RelayPointRequest request) {
        String fingerprint = fingerprint("RELAY_POINT", request.requestId(), shipmentNo,
                String.valueOf(request.waypointSeq()), String.valueOf(request.personnelControlEnabled()));
        return withIdempotency(request.requestId(), fingerprint,
                () -> tx.execute(status -> doSetRelayPoint(shipmentNo, request)));
    }

    private ServiceResult doSetRelayPoint(String shipmentNo, RelayPointRequest request) {
        Shipment shipment = loadShipment(shipmentNo);
        repository.lockShipment(shipment.id());
        shipment = repository.findShipmentById(shipment.id()).orElseThrow();
        if (shipment.status().isTerminal()) {
            throw ApiException.conflict("SHIPMENT_TERMINAL",
                    "运单终态 " + shipment.status() + "，不可设置接力点");
        }
        if (shipment.status() == ShipmentStatus.APPLIED) {
            throw ApiException.conflict("INVALID_STATUS", "运单尚未核准路线，不可设置接力点");
        }
        RouteApproval approval = currentApproval(shipment);
        List<ApprovalWaypoint> waypoints = repository.findWaypoints(approval.id());
        ApprovalWaypoint target = waypoints.stream()
                .filter(w -> w.seq() == request.waypointSeq()).findFirst()
                .orElseThrow(() -> ApiException.badRequest("WAYPOINT_NOT_FOUND",
                        "当前路线无途经点序号 " + request.waypointSeq()));
        NightCurfew curfew = applicableCurfew(shipment, waypoints);
        if (curfew != null && !target.plannedTime().isBefore(curfew.curfewStart())) {
            throw ApiException.badRequest("RELAY_POINT_UNSAFE",
                    "途经点 " + target.seq() + " 计划通过时刻 " + target.plannedTime()
                            + " 不早于夜间禁行起点 " + curfew.curfewStart() + "，不能作为安全接力点");
        }
        if (repository.findSegment(shipment.id(), approval.id(), target.seq()).isPresent()) {
            throw ApiException.conflict("RELAY_POINT_DUPLICATE",
                    "途经点 " + target.seq() + " 已设置接力段（运单+途经点序号去重）");
        }
        LocalDateTime now = now();
        long segmentId;
        try {
            segmentId = repository.insertRelaySegment(shipment.id(), approval.id(), target.seq(),
                    request.personnelControlEnabled(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("RELAY_POINT_DUPLICATE",
                    "途经点 " + target.seq() + " 已设置接力段（运单+途经点序号去重）");
        }
        try {
            // 主键冲突即并发占用失败：同一运单同时刻只允许一个进行中接力段。
            repository.occupyRelay(shipment.id(), segmentId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("RELAY_SEGMENT_ACTIVE",
                    "同一运单同时刻只允许一个进行中接力段");
        }
        repository.insertEvent(shipment.id(), EVT_RELAY_POINT_SET,
                "设置安全接力点：途经点 " + target.seq() + "(" + target.name() + ")，计划通过 "
                        + target.plannedTime(), now);
        Map<String, Object> body = shipmentView(shipmentNo);
        body.put("relaySegmentId", segmentId);
        return ServiceResult.ok(body);
    }

    /**
     * 接力双人打卡：交班（OUT）与接班（IN）各一条，双方编号须不同（单人打卡不生效）；
     * 接班时刻须严格晚于交班时刻，否则逆序拒绝并告警；双方齐全即完成换班并释放接力段占用。
     */
    public ServiceResult relayCheckin(String shipmentNo, RelayCheckinRequest request) {
        String fingerprint = fingerprint("RELAY_CHECKIN", request.requestId(), shipmentNo,
                request.escortId(), request.role(), request.checkTime().toString());
        return withIdempotency(request.requestId(), fingerprint,
                () -> tx.execute(status -> doRelayCheckin(shipmentNo, request)));
    }

    private ServiceResult doRelayCheckin(String shipmentNo, RelayCheckinRequest request) {
        EscortRole role = EscortRole.parse(request.role());
        if (role == null) {
            throw ApiException.badRequest("INVALID_ROLE",
                    "打卡角色须为 OUT（交班）或 IN（接班）: " + request.role());
        }
        Shipment shipment = loadShipment(shipmentNo);
        // 行锁串行化换班完成与禁行冻结的并发终态竞争。
        repository.lockShipment(shipment.id());
        shipment = repository.findShipmentById(shipment.id()).orElseThrow();
        LocalDateTime now = now();
        if (shipment.status().isTerminal()) {
            throw ApiException.conflict("SHIPMENT_TERMINAL",
                    "运单终态 " + shipment.status() + "，不可接力打卡");
        }
        if (shipment.status() != ShipmentStatus.DISPATCHED && shipment.status() != ShipmentStatus.IN_TRANSIT) {
            throw ApiException.conflict("NOT_DISPATCHED",
                    "运单当前状态 " + shipment.status() + "，不可接力打卡");
        }
        // 恰满禁行起点即判超时：懒冻结（冻结与处置登记同一事务提交）后返回 409。
        if (freezeIfCurfewOverdue(shipment, now)) {
            return curfewFrozenResult(shipmentNo);
        }
        RelaySegment segment = repository.findActiveSegment(shipment.id())
                .orElseThrow(() -> ApiException.conflict("RELAY_SEGMENT_NOT_FOUND",
                        "运单无进行中接力段，请先设置安全接力点"));
        RouteApproval approval = currentApproval(shipment);
        NightCurfew curfew = applicableCurfew(shipment, repository.findWaypoints(approval.id()));
        if (curfew != null && !request.checkTime().isBefore(curfew.curfewStart())) {
            throw ApiException.badRequest("RELAY_CHECKIN_TOO_LATE",
                    "接力打卡时刻 " + request.checkTime() + " 须严格早于夜间禁行起点 " + curfew.curfewStart());
        }
        if (repository.findRelayCheckin(segment.id(), role).isPresent()) {
            throw ApiException.conflict("RELAY_CHECKIN_DUPLICATE",
                    "接力点 " + segment.relaySeq() + " 角色 " + role + " 已打卡（运单+途经点序号去重）");
        }
        // 人员管控门禁：仅约束受控段 IN 确认，不阻止原人员 OUT 离岗。
        if (segment.personnelControlled() && role == EscortRole.IN) {
            gateControlledIn(segment, request);
        }
        Optional<RelayCheckin> outCk = repository.findRelayCheckin(segment.id(), EscortRole.OUT);
        Optional<RelayCheckin> inCk = repository.findRelayCheckin(segment.id(), EscortRole.IN);
        Optional<RelayCheckin> opposite = role == EscortRole.OUT ? inCk : outCk;
        if (opposite.isPresent() && opposite.get().escortId().equals(request.escortId())) {
            throw ApiException.badRequest("RELAY_SAME_ESCORT",
                    "交接班押运员编号相同（" + request.escortId() + "），单人打卡不生效");
        }
        // 时序校验：接班时刻须严格晚于交班时刻；乱序回传按时刻归位，逆序拒绝并告警。
        if (role == EscortRole.IN && outCk.isPresent()
                && !request.checkTime().isAfter(outCk.get().checkTime())) {
            rejectRelayReverse(shipment, segment, request,
                    "未晚于交班时刻 " + outCk.get().checkTime(), now);
        }
        if (role == EscortRole.OUT && inCk.isPresent()
                && !inCk.get().checkTime().isAfter(request.checkTime())) {
            rejectRelayReverse(shipment, segment, request,
                    "交班后已有接班时刻 " + inCk.get().checkTime() + " 不晚于本次交班时刻", now);
        }
        repository.insertRelayCheckin(segment.id(), shipment.id(), request.escortId(), role,
                request.checkTime(), request.requestId(), now);

        Optional<RelayCheckin> outFinal = repository.findRelayCheckin(segment.id(), EscortRole.OUT);
        Optional<RelayCheckin> inFinal = repository.findRelayCheckin(segment.id(), EscortRole.IN);
        Map<String, Object> body;
        if (outFinal.isPresent() && inFinal.isPresent()) {
            RelayCheckin out = outFinal.get();
            RelayCheckin in = inFinal.get();
            int rows = repository.completeSegment(segment.id(), out.escortId(), out.checkTime(),
                    in.escortId(), in.checkTime(), in.checkTime());
            if (rows == 0) {
                throw ApiException.conflict("RELAY_SEGMENT_CLOSED", "接力段已被并发完成或冻结，请重试");
            }
            // 受控段换班完成：同事务核销人员预约，班段占用结束。
            if (segment.personnelControlled()) {
                repository.findActiveReservationBySegment(segment.id())
                        .ifPresent(r -> repository.consumeReservation(r.id(), now));
            }
            repository.releaseRelay(shipment.id());
            repository.insertEvent(shipment.id(), EVT_RELAY_HANDOVER,
                    "接力点 " + segment.relaySeq() + " 换班完成：交班 " + out.escortId() + "@"
                            + out.checkTime() + "，接班 " + in.escortId() + "@" + in.checkTime(), now);
            body = shipmentView(shipmentNo);
            body.put("handoverCompleted", true);
        } else {
            // 单人打卡不生效：仅记录一侧打卡，换班未完成。
            body = shipmentView(shipmentNo);
            body.put("handoverCompleted", false);
            body.put("message", "已记录 " + role + " 打卡，待对方打卡后换班生效（单人打卡不生效）");
        }
        return ServiceResult.ok(body);
    }

    private void rejectRelayReverse(Shipment shipment, RelaySegment segment,
                                    RelayCheckinRequest request, String detail, LocalDateTime now) {
        String message = "接力点 " + segment.relaySeq() + " " + request.role() + " 打卡时刻 "
                + request.checkTime() + " " + detail + "，逆序拒绝";
        eventRecorder.recordNewTransaction(shipment.id(), EVT_RELAY_CHECKIN_REVERSED, message, now);
        throw ApiException.badRequest("RELAY_CHECKIN_REVERSED", message);
    }

    /**
     * 受控段 IN 门禁（须在事务内、已持有运单行锁后调用）：
     * 必须存在该接力段的占用中预约且来自预约人员；实际接班时刻（Asia/Shanghai 转 UTC）
     * 落在预约班段 [start,end) 内（恰等于 end 拒绝）；行锁重查资格未撤销，
     * 与资格撤销形成一致次序。
     */
    private void gateControlledIn(RelaySegment segment, RelayCheckinRequest request) {
        EscortReservation reservation = repository.findActiveReservationBySegment(segment.id())
                .orElseThrow(() -> ApiException.conflict("RESERVATION_REQUIRED",
                        "接力点 " + segment.relaySeq() + " 已启用人员管控，须先完成人员资格预约"));
        if (!reservation.escortId().equals(request.escortId())) {
            throw ApiException.conflict("RELAY_IN_NOT_RESERVED",
                    "受控段 IN 确认必须来自预约人员 " + reservation.escortId() + "，实际 " + request.escortId());
        }
        LocalDateTime checkTimeUtc = toUtc(request.checkTime());
        if (checkTimeUtc.isBefore(reservation.shiftStartUtc())
                || !checkTimeUtc.isBefore(reservation.shiftEndUtc())) {
            throw ApiException.badRequest("RELAY_IN_OUT_OF_SHIFT",
                    "实际接班时刻 " + request.checkTime() + "（UTC " + checkTimeUtc + "）不在预约班段 ["
                            + reservation.shiftStartUtc() + ", " + reservation.shiftEndUtc() + ") 内");
        }
        EscortQualification qualification = repository.lockQualification(reservation.qualificationId())
                .orElseThrow(() -> ApiException.conflict("QUALIFICATION_REVOKED",
                        "预约依据的资格记录 " + reservation.qualificationId() + " 不存在"));
        if (qualification.revoked()) {
            throw ApiException.conflict("QUALIFICATION_REVOKED",
                    "接班员 " + request.escortId() + " 的资格 " + qualification.id() + " 已撤销，禁止接班确认");
        }
    }

    /** 业务时刻（Asia/Shanghai）转 UTC，用于与班段/资格 UTC 区间比较。 */
    private static LocalDateTime toUtc(LocalDateTime chinaTime) {
        return chinaTime.atZone(MutableClock.ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    // ============================ 人员资格与预约 ============================

    /**
     * 新增接班员合成资格：版本从 1 起；requestId 幂等（指纹覆盖全部字段与精确时刻，
     * 失败不占键），资格写入与幂等结果同一事务提交。
     */
    public ServiceResult createQualification(QualificationCreateRequest request) {
        String fingerprint = fingerprint("QUAL_CREATE", request.requestId(), request.escortId(),
                request.goodsCategory(), request.validFromUtc().toString(), request.validToUtc().toString());
        return withIdempotencyAtomic(request.requestId(), fingerprint, () -> doCreateQualification(request));
    }

    private ServiceResult doCreateQualification(QualificationCreateRequest request) {
        repository.findCategory(request.goodsCategory())
                .orElseThrow(() -> ApiException.badRequest("CATEGORY_NOT_FOUND",
                        "危化品类别不存在: " + request.goodsCategory()));
        if (!request.validFromUtc().isBefore(request.validToUtc())) {
            throw ApiException.badRequest("QUALIFICATION_INTERVAL_INVALID",
                    "资格有效区间起止无序：[" + request.validFromUtc() + ", " + request.validToUtc() + "]");
        }
        LocalDateTime now = now();
        long qualificationId = repository.insertQualification(request.escortId(), request.goodsCategory(),
                request.validFromUtc(), request.validToUtc(), request.requestId(), now);
        return ServiceResult.ok(qualificationView(
                repository.findQualification(qualificationId).orElseThrow()));
    }

    /**
     * 撤销资格（乐观锁：expectedVersion 匹配才成功，版本加 1，过期 409）。
     * 与受控段 IN 经资格行锁形成一致次序：已成功换班不回滚，但留下风险证据事件，
     * 且撤销后该人员后续预约因资格已撤销被拒绝。
     */
    public ServiceResult revokeQualification(long qualificationId, QualificationRevokeRequest request) {
        String fingerprint = fingerprint("QUAL_REVOKE", request.requestId(),
                String.valueOf(qualificationId), String.valueOf(request.expectedVersion()));
        return withIdempotencyAtomic(request.requestId(), fingerprint,
                () -> doRevokeQualification(qualificationId, request));
    }

    private ServiceResult doRevokeQualification(long qualificationId, QualificationRevokeRequest request) {
        EscortQualification qualification = repository.lockQualification(qualificationId)
                .orElseThrow(() -> ApiException.notFound("QUALIFICATION_NOT_FOUND",
                        "资格记录不存在: " + qualificationId));
        if (qualification.revoked()) {
            throw ApiException.conflict("QUALIFICATION_ALREADY_REVOKED",
                    "资格 " + qualificationId + " 已撤销");
        }
        if (qualification.versionNo() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "资格 " + qualificationId + " 当前版本 " + qualification.versionNo()
                            + "，期望 " + request.expectedVersion() + "，版本过期");
        }
        LocalDateTime now = now();
        repository.revokeQualification(qualificationId, request.expectedVersion(), now);
        // 风险证据：用该资格完成换班的预约不回滚，逐运单留痕。
        for (EscortReservation reservation : repository.findReservationsByQualification(qualificationId)) {
            if (reservation.status() == ReservationStatus.CONSUMED) {
                repository.insertEvent(reservation.shipmentId(), EVT_QUALIFICATION_REVOKED_RISK,
                        "资格 " + qualificationId + "（接班员 " + qualification.escortId()
                                + "）在换班完成后被撤销：已完成换班不回滚，该人员后续预约将被拒绝", now);
            }
        }
        return ServiceResult.ok(qualificationView(
                repository.findQualification(qualificationId).orElseThrow()));
    }

    public ServiceResult getQualification(long qualificationId) {
        EscortQualification qualification = repository.findQualification(qualificationId)
                .orElseThrow(() -> ApiException.notFound("QUALIFICATION_NOT_FOUND",
                        "资格记录不存在: " + qualificationId));
        return ServiceResult.ok(qualificationView(qualification));
    }

    private Map<String, Object> qualificationView(EscortQualification qualification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qualificationId", qualification.id());
        body.put("escortId", qualification.escortId());
        body.put("goodsCategory", qualification.goodsCategory());
        body.put("validFromUtc", qualification.validFromUtc().toString());
        body.put("validToUtc", qualification.validToUtc().toString());
        body.put("revoked", qualification.revoked());
        body.put("versionNo", qualification.versionNo());
        return body;
    }

    /**
     * 人员资格预约：为启用人员管控的接力段预约接班员班段 [start,end)（UTC）。
     * 校验（货类/资格区间覆盖/撤销状态/跨运单接力点/同员班段重叠）与人员占用同一事务提交；
     * 同一接班员的并发预约经其资格行锁串行化。
     */
    public ServiceResult createReservation(String shipmentNo, long segmentId, ReservationCreateRequest request) {
        String fingerprint = fingerprint("RESV_CREATE", request.requestId(), shipmentNo,
                String.valueOf(segmentId), request.escortId(),
                request.shiftStartUtc().toString(), request.shiftEndUtc().toString());
        return withIdempotencyAtomic(request.requestId(), fingerprint,
                () -> doCreateReservation(shipmentNo, segmentId, request));
    }

    private ServiceResult doCreateReservation(String shipmentNo, long segmentId, ReservationCreateRequest request) {
        Shipment shipment = loadShipment(shipmentNo);
        repository.lockShipment(shipment.id());
        shipment = repository.findShipmentById(shipment.id()).orElseThrow();
        if (shipment.status().isTerminal()) {
            throw ApiException.conflict("SHIPMENT_TERMINAL",
                    "运单终态 " + shipment.status() + "，不可预约人员");
        }
        RelaySegment segment = repository.findSegmentById(segmentId)
                .orElseThrow(() -> ApiException.notFound("RELAY_SEGMENT_NOT_FOUND",
                        "接力段不存在: " + segmentId));
        if (segment.shipmentId() != shipment.id()) {
            throw ApiException.badRequest("RELAY_SEGMENT_MISMATCH",
                    "接力段 " + segmentId + " 不属于运单 " + shipmentNo + "，跨运单接力点拒绝");
        }
        if (!segment.personnelControlled()) {
            throw ApiException.badRequest("RELAY_SEGMENT_NOT_CONTROLLED",
                    "接力段 " + segmentId + " 未启用人员管控，不接受资格预约");
        }
        if (segment.status() != RelaySegmentStatus.IN_PROGRESS) {
            throw ApiException.conflict("RELAY_SEGMENT_CLOSED",
                    "接力段 " + segmentId + " 状态 " + segment.status() + "，不可预约");
        }
        if (!request.shiftStartUtc().isBefore(request.shiftEndUtc())) {
            throw ApiException.badRequest("SHIFT_INTERVAL_INVALID",
                    "班段起止无序：[" + request.shiftStartUtc() + ", " + request.shiftEndUtc() + ")");
        }
        // 锁定该接班员全部资格行：资格校验与重叠判定串行化，校验与占用同一事务提交。
        final String goodsCategory = shipment.goodsCategory();
        List<EscortQualification> qualifications = repository.lockQualificationsByEscort(request.escortId());
        List<EscortQualification> forCategory = qualifications.stream()
                .filter(q -> q.goodsCategory().equals(goodsCategory)).toList();
        if (forCategory.isEmpty()) {
            throw ApiException.badRequest("QUALIFICATION_CATEGORY_MISMATCH",
                    "接班员 " + request.escortId() + " 无可押运货类 " + goodsCategory + " 的资格");
        }
        EscortQualification qualification = forCategory.stream()
                .filter(q -> !q.revoked()
                        && !q.validFromUtc().isAfter(request.shiftStartUtc())
                        && !q.validToUtc().isBefore(request.shiftEndUtc()))
                .findFirst()
                .orElse(null);
        if (qualification == null) {
            boolean anyActive = forCategory.stream().anyMatch(q -> !q.revoked());
            if (anyActive) {
                throw ApiException.badRequest("SHIFT_NOT_COVERED",
                        "班段 [" + request.shiftStartUtc() + ", " + request.shiftEndUtc()
                                + ") 未被接班员 " + request.escortId() + " 的资格区间完整覆盖");
            }
            throw ApiException.conflict("QUALIFICATION_REVOKED",
                    "接班员 " + request.escortId() + " 的资格已撤销，禁止预约");
        }
        // 班段重叠判定（半开区间：端点相接允许，内部重叠拒绝）。
        for (EscortReservation existing : repository.findActiveReservationsByEscort(request.escortId())) {
            if (existing.shiftStartUtc().isBefore(request.shiftEndUtc())
                    && request.shiftStartUtc().isBefore(existing.shiftEndUtc())) {
                throw ApiException.conflict("ESCORT_SHIFT_OVERLAP",
                        "接班员 " + request.escortId() + " 已占用班段 [" + existing.shiftStartUtc() + ", "
                                + existing.shiftEndUtc() + ")（运单 " + existing.shipmentId() + "），与本次班段内部重叠");
            }
        }
        if (repository.findActiveReservationBySegment(segmentId).isPresent()) {
            throw ApiException.conflict("SEGMENT_ALREADY_RESERVED",
                    "接力段 " + segmentId + " 已存在占用中的人员预约");
        }
        LocalDateTime now = now();
        long reservationId = repository.insertReservation(shipment.id(), segmentId, request.escortId(),
                qualification.id(), qualification.versionNo(),
                request.shiftStartUtc(), request.shiftEndUtc(), request.requestId(), now);
        repository.insertEvent(shipment.id(), EVT_RESERVATION_CREATED,
                "接力点 " + segment.relaySeq() + " 预约接班员 " + request.escortId() + "，班段 ["
                        + request.shiftStartUtc() + ", " + request.shiftEndUtc() + ")（UTC），依据资格 "
                        + qualification.id() + " v" + qualification.versionNo(), now);
        Map<String, Object> body = shipmentView(shipmentNo);
        body.put("reservationId", reservationId);
        return ServiceResult.ok(body);
    }

    /**
     * 撤销人员预约（乐观锁）：仅首次换班打卡前可撤销并释放班段占用；
     * 已开始打卡（任一角色）则不得撤销。
     */
    public ServiceResult revokeReservation(String shipmentNo, long segmentId, long reservationId,
                                           ReservationRevokeRequest request) {
        String fingerprint = fingerprint("RESV_REVOKE", request.requestId(), shipmentNo,
                String.valueOf(segmentId), String.valueOf(reservationId),
                String.valueOf(request.expectedVersion()));
        return withIdempotencyAtomic(request.requestId(), fingerprint,
                () -> doRevokeReservation(shipmentNo, segmentId, reservationId, request));
    }

    private ServiceResult doRevokeReservation(String shipmentNo, long segmentId, long reservationId,
                                              ReservationRevokeRequest request) {
        Shipment shipment = loadShipment(shipmentNo);
        repository.lockShipment(shipment.id());
        EscortReservation reservation = repository.findReservation(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND",
                        "预约不存在: " + reservationId));
        if (reservation.shipmentId() != shipment.id() || reservation.segmentId() != segmentId) {
            throw ApiException.badRequest("RESERVATION_MISMATCH",
                    "预约 " + reservationId + " 不属于运单 " + shipmentNo + " 的接力段 " + segmentId);
        }
        if (reservation.versionNo() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "预约 " + reservationId + " 当前版本 " + reservation.versionNo()
                            + "，期望 " + request.expectedVersion() + "，版本过期");
        }
        if (reservation.status() != ReservationStatus.ACTIVE) {
            throw ApiException.conflict("RESERVATION_NOT_ACTIVE",
                    "预约 " + reservationId + " 状态 " + reservation.status() + "，不可撤销");
        }
        if (!repository.findRelayCheckins(segmentId).isEmpty()) {
            throw ApiException.conflict("RESERVATION_LOCKED",
                    "接力段 " + segmentId + " 已开始换班打卡，预约不得撤销");
        }
        LocalDateTime now = now();
        repository.transitionReservation(reservationId, ReservationStatus.ACTIVE,
                ReservationStatus.RELEASED, request.expectedVersion(), now);
        repository.insertEvent(shipment.id(), EVT_RESERVATION_RELEASED,
                "撤销接班员 " + reservation.escortId() + " 的预约 " + reservationId + "，班段占用释放", now);
        Map<String, Object> body = shipmentView(shipmentNo);
        body.put("reservationId", reservationId);
        body.put("reservationReleased", true);
        return ServiceResult.ok(body);
    }

    // ============================ 夜间禁行判定与冻结 ============================

    /**
     * 适用禁行规则：计划运输窗口（计划发运 → 最后途经点计划通过时刻）与禁行窗口
     * [curfewStart, curfewEnd) 相交（计划到达不早于禁行起点）时返回该规则，否则 null。
     */
    private NightCurfew applicableCurfew(Shipment shipment, List<ApprovalWaypoint> waypoints) {
        RouteApproval approval = currentApproval(shipment);
        List<NightCurfew> curfews = repository.findNightCurfews(approval.routeCode());
        if (curfews.isEmpty() || waypoints.isEmpty()) {
            return null;
        }
        LocalDateTime lastPlanned = waypoints.stream()
                .map(ApprovalWaypoint::plannedTime).max(LocalDateTime::compareTo).orElseThrow();
        for (NightCurfew curfew : curfews) {
            if (shipment.plannedDeparture().isBefore(curfew.curfewEnd())
                    && !lastPlanned.isBefore(curfew.curfewStart())) {
                return curfew;
            }
        }
        return null;
    }

    /** 换班是否已在禁行起点前完成：存在 COMPLETED 接力段且完成时刻严格早于禁行起点。 */
    private boolean handoverCompletedBefore(long shipmentId, LocalDateTime curfewStart) {
        return repository.findSegments(shipmentId).stream()
                .anyMatch(s -> s.status() == RelaySegmentStatus.COMPLETED
                        && s.completedAt() != null && s.completedAt().isBefore(curfewStart));
    }

    /**
     * 夜间禁行冻结判定与执行（须在事务内、已持有运单行锁后调用）。
     * 恰满禁行起点即判超时；冻结与处置登记在同一事务内完成；
     * 终态 CAS 保证与核销/失联竞争时形成唯一终态。返回 true 表示本次冻结成功。
     */
    private boolean freezeIfCurfewOverdue(Shipment shipment, LocalDateTime now) {
        if (shipment.status() != ShipmentStatus.DISPATCHED && shipment.status() != ShipmentStatus.IN_TRANSIT) {
            return false;
        }
        RouteApproval approval = currentApproval(shipment);
        List<ApprovalWaypoint> waypoints = repository.findWaypoints(approval.id());
        NightCurfew curfew = applicableCurfew(shipment, waypoints);
        if (curfew == null || now.isBefore(curfew.curfewStart())) {
            return false;
        }
        if (handoverCompletedBefore(shipment.id(), curfew.curfewStart())) {
            return false;
        }
        int rows = repository.compareAndSetTerminal(shipment.id(), ShipmentStatus.CURFEW_FROZEN);
        if (rows == 0) {
            // 终态竞争败者：核销/失联已先行，唯一终态。
            return false;
        }
        repository.freezeActiveSegments(shipment.id());
        Long segmentId = repository.findSegments(shipment.id()).stream()
                .filter(s -> s.status() == RelaySegmentStatus.FROZEN)
                .map(RelaySegment::id).findFirst().orElse(null);
        repository.releaseRelay(shipment.id());
        repository.releaseVehicle(shipment.vehiclePlate());
        repository.releaseActiveReservations(shipment.id(), now);
        String reason = "未在夜间禁行起点 " + curfew.curfewStart() + " 前完成押运换班，运单冻结并转处置";
        repository.insertDisposal(shipment.id(), segmentId, reason, curfew.curfewStart(), now);
        repository.insertEvent(shipment.id(), EVT_CURFEW_FROZEN, reason, now);
        return true;
    }

    /** 懒冻结后的 409 响应（冻结与处置已随本事务提交）。 */
    private ServiceResult curfewFrozenResult(String shipmentNo) {
        Map<String, Object> body = shipmentView(shipmentNo);
        body.put("errorCode", "CURFEW_FROZEN");
        body.put("message", "未在夜间禁行起点前完成押运换班，运单已冻结并转处置，新打卡被拒绝");
        return ServiceResult.status(409, body);
    }

    // ============================ 紧急改线 ============================

    public ServiceResult reroute(String shipmentNo, RerouteRequest request) {
        String fingerprint = fingerprint("REROUTE", request.requestId(), shipmentNo, request.routeCode());
        return withIdempotency(request.requestId(), fingerprint,
                () -> tx.execute(status -> doReroute(shipmentNo, request)));
    }

    private ServiceResult doReroute(String shipmentNo, RerouteRequest request) {
        Shipment shipment = loadShipment(shipmentNo);
        if (shipment.status().isTerminal()) {
            throw ApiException.conflict("SHIPMENT_TERMINAL", "运单已终态，不可改线");
        }
        if (shipment.status() != ShipmentStatus.DISPATCHED && shipment.status() != ShipmentStatus.IN_TRANSIT) {
            throw ApiException.conflict("INVALID_STATUS", "仅发运后可紧急改线，当前 " + shipment.status());
        }
        repository.findRoute(request.routeCode())
                .orElseThrow(() -> ApiException.badRequest("ROUTE_NOT_FOUND",
                        "路线不存在: " + request.routeCode()));
        List<RouteApproval> approvals = repository.findApprovals(shipment.id());
        boolean pendingExists = approvals.stream()
                .anyMatch(a -> a.status() == RouteApprovalStatus.PENDING);
        if (pendingExists) {
            throw ApiException.conflict("REROUTE_PENDING", "已有待核准改线申请，请先完成核准");
        }
        int nextVersion = approvals.stream().mapToInt(RouteApproval::versionNo).max().orElse(0) + 1;
        LocalDateTime now = now();
        long newApprovalId = repository.insertApproval(shipment.id(), request.routeCode(), nextVersion,
                RouteApprovalStatus.PENDING, null, now);
        List<RouteWaypointDef> defs = repository.findWaypointDefs(request.routeCode());
        for (RouteWaypointDef def : defs) {
            repository.insertWaypoint(newApprovalId, shipment.id(), def.seq(), def.name(),
                    shipment.plannedDeparture().plusMinutes(def.offsetMinutes()), WaypointState.PENDING);
        }
        Map<String, Object> body = shipmentView(shipmentNo);
        body.put("pendingApprovalId", newApprovalId);
        body.put("message", "改线申请已提交，核准通过前原路线打卡继续有效");
        return ServiceResult.ok(body);
    }

    public ServiceResult approveReroute(String shipmentNo, long approvalId) {
        return tx.execute(status -> doApproveReroute(shipmentNo, approvalId));
    }

    private ServiceResult doApproveReroute(String shipmentNo, long approvalId) {
        Shipment shipment = loadShipment(shipmentNo);
        RouteApproval next = repository.findApproval(approvalId)
                .orElseThrow(() -> ApiException.notFound("APPROVAL_NOT_FOUND", "核准记录不存在"));
        if (next.shipmentId() != shipment.id()) {
            throw ApiException.badRequest("APPROVAL_MISMATCH", "核准记录不属于该运单");
        }
        if (next.status() != RouteApprovalStatus.PENDING || next.versionNo() <= 1) {
            throw ApiException.conflict("APPROVAL_NOT_PENDING", "该记录不是待核准改线申请");
        }
        LocalDateTime now = now();
        RouteRuleEvaluator.Result result = evaluate(next.routeCode(),
                shipment.goodsCategory(), shipment.plannedDeparture());
        if (!result.allowed()) {
            // 改线驳回仅落库新记录的驳回结果，原路线继续有效；提交后返回 422。
            repository.updateApprovalStatus(next.id(), RouteApprovalStatus.REJECTED, now);
            repository.insertEvent(shipment.id(), EVT_REROUTE_REJECTED, result.message(), now);
            Map<String, Object> body = shipmentView(shipmentNo);
            body.put("errorCode", "REROUTE_REJECTED");
            body.put("message", result.message());
            return ServiceResult.status(422, body);
        }
        // 同一事务：旧路线终止 + 新路线生效 + 运单指向切换。
        RouteApproval old = currentApproval(shipment);
        repository.updateApprovalStatus(old.id(), RouteApprovalStatus.TERMINATED, now);
        repository.updateApprovalStatus(next.id(), RouteApprovalStatus.APPROVED, now);
        repository.updateCurrentApproval(shipment.id(), next.id());
        if (shipment.status() == ShipmentStatus.DISPATCHED) {
            repository.updateStatus(shipment.id(), ShipmentStatus.IN_TRANSIT);
        }
        repository.insertEvent(shipment.id(), EVT_REROUTE_SWITCH,
                "路线由 " + old.routeCode() + "(v" + old.versionNo() + ") 切换为 "
                        + next.routeCode() + "(v" + next.versionNo() + ")，原路线打卡保留", now);
        return ServiceResult.ok(shipmentView(shipmentNo));
    }

    // ============================ 到达核销 ============================

    public ServiceResult complete(String shipmentNo, String requestId) {
        String fingerprint = fingerprint("COMPLETE", requestId, shipmentNo);
        return withIdempotency(requestId, fingerprint,
                () -> tx.execute(status -> doComplete(shipmentNo)));
    }

    private ServiceResult doComplete(String shipmentNo) {
        Shipment shipment = loadShipment(shipmentNo);
        if (shipment.status() == ShipmentStatus.COMPLETED) {
            // 结案终态：重复核销（无论是否同 requestId）返回幂等成功。
            return ServiceResult.ok(shipmentView(shipmentNo));
        }
        if (shipment.status() == ShipmentStatus.LOST_CONTACT) {
            throw ApiException.conflict("ALREADY_LOST_CONTACT", "运单已失联冻结，不可核销");
        }
        if (shipment.status() == ShipmentStatus.CURFEW_FROZEN) {
            throw ApiException.conflict("ALREADY_CURFEW_FROZEN", "运单已禁行冻结转处置，不可核销");
        }
        // 条件更新：与失联升级/禁行冻结竞争，败者拿不到终态。
        int rows = repository.compareAndSetTerminal(shipment.id(), ShipmentStatus.COMPLETED);
        if (rows == 0) {
            Shipment current = repository.findShipmentById(shipment.id()).orElseThrow();
            if (current.status() == ShipmentStatus.COMPLETED) {
                return ServiceResult.ok(shipmentView(shipmentNo));
            }
            if (current.status() == ShipmentStatus.CURFEW_FROZEN) {
                throw ApiException.conflict("ALREADY_CURFEW_FROZEN", "运单已禁行冻结转处置，不可核销");
            }
            throw ApiException.conflict("ALREADY_LOST_CONTACT", "运单已失联冻结，不可核销");
        }
        repository.releaseVehicle(shipment.vehiclePlate());
        repository.releaseActiveReservations(shipment.id(), now());
        return ServiceResult.ok(shipmentView(shipmentNo));
    }

    // ============================ 超时/失联监控（可复算） ============================

    public ServiceResult scanTimeouts(LocalDateTime at) {
        LocalDateTime now = at != null ? at : now();
        List<Map<String, Object>> affected = new ArrayList<>();
        List<Shipment> actives;
        try {
            actives = tx.execute(status -> repository.findActiveShipments());
        } catch (Exception e) {
            throw ApiException.badRequest("SCAN_FAILED", e.getMessage());
        }
        for (Shipment shipment : actives) {
            if (shipment.status() != ShipmentStatus.DISPATCHED && shipment.status() != ShipmentStatus.IN_TRANSIT) {
                continue;
            }
            try {
                Map<String, Object> changed = tx.execute(status -> scanOne(shipment.id(), now));
                if (changed != null && Boolean.TRUE.equals(changed.get("changed"))) {
                    affected.add(changed);
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception ignored) {
                // 单个运单扫描失败不影响其他运单（终态竞争败者等）。
            }
        }
        return ServiceResult.ok(Map.of("scanAt", now.toString(), "affected", affected));
    }

    /** 扫描单个运单：标记超时点；夜间禁行冻结与连续 2 点超时失联共用终态 CAS，保证唯一终态。 */
    private Map<String, Object> scanOne(long shipmentId, LocalDateTime now) {
        repository.lockShipment(shipmentId);
        Shipment shipment = repository.findShipmentById(shipmentId).orElseThrow();
        if (shipment.status().isTerminal()) {
            return Map.of();
        }
        long approvalId = shipment.currentApprovalId();
        List<ApprovalWaypoint> waypoints = repository.findWaypoints(approvalId);
        List<Integer> timedOut = new ArrayList<>();
        for (ApprovalWaypoint w : waypoints) {
            if (w.state() == WaypointState.PENDING && !now.isBefore(w.deadline())) {
                int rows = repository.compareAndSetWaypointState(approvalId, w.seq(),
                        WaypointState.PENDING, WaypointState.TIMED_OUT, null);
                if (rows == 1) {
                    timedOut.add(w.seq());
                    repository.insertEvent(shipmentId, EVT_DEVIATION_WARNING,
                            "途经点 " + w.seq() + "(" + w.name() + ") 计划通过时刻 " + w.plannedTime()
                                    + "，超时阈值 " + w.deadline() + "，扫描时刻 " + now + " 仍未打卡", now);
                }
            }
        }
        // 偏离预警与禁行冻结同时命中：先判禁行冻结，冻结成功即唯一终态，不再继续计程/升级失联。
        boolean curfewFrozen = freezeIfCurfewOverdue(shipment, now);
        boolean upgraded = false;
        if (!curfewFrozen) {
            boolean lost = hasConsecutiveTimeouts(repository.findWaypoints(approvalId));
            if (lost) {
                int rows = repository.compareAndSetTerminal(shipmentId, ShipmentStatus.LOST_CONTACT);
                if (rows == 1) {
                    upgraded = true;
                    repository.freezeVehicle(shipment.vehiclePlate(), shipmentId, now);
                    repository.releaseVehicle(shipment.vehiclePlate());
                    repository.releaseActiveReservations(shipmentId, now);
                    repository.insertEvent(shipmentId, EVT_LOST_CONTACT,
                            "连续 2 个途经点超时未打卡，升级失联并冻结车辆 " + shipment.vehiclePlate(), now);
                }
            }
        }
        if (timedOut.isEmpty() && !upgraded && !curfewFrozen) {
            return Map.of();
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("changed", true);
        changed.put("shipmentNo", shipment.shipmentNo());
        changed.put("newlyTimedOut", timedOut);
        changed.put("lostContact", upgraded);
        changed.put("curfewFrozen", curfewFrozen);
        return changed;
    }

    /** 连续超时判定：按序号相邻的两个途经点均处于 TIMED_OUT（恰 2 个即触发）。 */
    static boolean hasConsecutiveTimeouts(List<ApprovalWaypoint> waypoints) {
        List<ApprovalWaypoint> sorted = waypoints.stream()
                .sorted(java.util.Comparator.comparingInt(ApprovalWaypoint::seq)).toList();
        int consecutive = 0;
        for (ApprovalWaypoint w : sorted) {
            if (w.state() == WaypointState.TIMED_OUT) {
                consecutive++;
                if (consecutive >= LOST_CONTACT_CONSECUTIVE_TIMEOUTS) {
                    return true;
                }
            } else {
                consecutive = 0;
            }
        }
        return false;
    }

    // ============================ 查询视图 ============================

    public ServiceResult getShipment(String shipmentNo) {
        return ServiceResult.ok(shipmentView(shipmentNo));
    }

    private Shipment loadShipment(String shipmentNo) {
        return repository.findShipmentByNo(shipmentNo)
                .orElseThrow(() -> ApiException.notFound("SHIPMENT_NOT_FOUND", "运单不存在: " + shipmentNo));
    }

    private RouteApproval currentApproval(Shipment shipment) {
        return repository.findApproval(shipment.currentApprovalId())
                .orElseThrow(() -> ApiException.badRequest("APPROVAL_NOT_FOUND", "当前生效路线核准记录缺失"));
    }

    private RouteRuleEvaluator.Result evaluate(String routeCode, String categoryCode, LocalDateTime departure) {
        GoodsCategory category = repository.findCategory(categoryCode)
                .orElseThrow(() -> ApiException.badRequest("CATEGORY_NOT_FOUND", "危化品类别不存在"));
        RouteInfo route = repository.findRoute(routeCode)
                .orElseThrow(() -> ApiException.badRequest("ROUTE_NOT_FOUND", "路线不存在"));
        return RouteRuleEvaluator.evaluate(route, category.requiredLevel(), departure,
                repository.findWaypointDefs(routeCode), repository.findControlPeriods(routeCode));
    }

    private Map<String, Object> baseShipmentView(Shipment shipment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shipmentNo", shipment.shipmentNo());
        body.put("vehiclePlate", shipment.vehiclePlate());
        body.put("goodsCategory", shipment.goodsCategory());
        body.put("status", shipment.status().name());
        body.put("terminal", shipment.status().isTerminal());
        body.put("plannedDeparture", shipment.plannedDeparture().toString());
        return body;
    }

    private Map<String, Object> shipmentView(String shipmentNo) {
        Shipment shipment = loadShipment(shipmentNo);
        Map<String, Object> body = baseShipmentView(shipment);
        repository.findVehicle(shipment.vehiclePlate()).ifPresent(v -> body.put("vehicleFrozen", v.frozen()));

        List<Map<String, Object>> approvalViews = new ArrayList<>();
        for (RouteApproval approval : repository.findApprovals(shipment.id())) {
            Map<String, Object> av = new LinkedHashMap<>();
            av.put("approvalId", approval.id());
            av.put("versionNo", approval.versionNo());
            av.put("routeCode", approval.routeCode());
            av.put("status", approval.status().name());
            av.put("rejectReason", approval.rejectReason());
            av.put("current", approval.id() == shipment.currentApprovalId());

            List<Checkin> checkins = repository.findCheckinsByApproval(approval.id());
            List<Map<String, Object>> wpViews = new ArrayList<>();
            for (ApprovalWaypoint w : repository.findWaypoints(approval.id())) {
                Map<String, Object> wv = new LinkedHashMap<>();
                wv.put("seq", w.seq());
                wv.put("name", w.name());
                wv.put("plannedTime", w.plannedTime().toString());
                wv.put("deadline", w.deadline().toString());
                wv.put("state", w.state().name());
                wv.put("checkedTime", w.checkedTime() == null ? null : w.checkedTime().toString());
                checkins.stream().filter(c -> c.seq() == w.seq()).findFirst()
                        .ifPresent(c -> wv.put("requestId", c.requestId()));
                wpViews.add(wv);
            }
            av.put("waypoints", wpViews);
            approvalViews.add(av);
        }
        body.put("approvals", approvalViews);

        List<Map<String, Object>> eventViews = new ArrayList<>();
        for (ShipmentEvent e : repository.findEvents(shipment.id())) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("eventType", e.eventType());
            ev.put("detail", e.detail());
            ev.put("eventTime", e.eventTime().toString());
            eventViews.add(ev);
        }
        body.put("events", eventViews);

        // 夜间接力押运视图：接力段（含双人打卡）、禁行规则、处置登记。
        List<Map<String, Object>> segmentViews = new ArrayList<>();
        for (RelaySegment segment : repository.findSegments(shipment.id())) {
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("segmentId", segment.id());
            sv.put("relaySeq", segment.relaySeq());
            sv.put("status", segment.status().name());
            sv.put("personnelControlled", segment.personnelControlled());
            sv.put("outEscortId", segment.outEscortId());
            sv.put("outTime", segment.outTime() == null ? null : segment.outTime().toString());
            sv.put("inEscortId", segment.inEscortId());
            sv.put("inTime", segment.inTime() == null ? null : segment.inTime().toString());
            sv.put("completedAt", segment.completedAt() == null ? null : segment.completedAt().toString());
            List<Map<String, Object>> checkinViews = new ArrayList<>();
            for (RelayCheckin c : repository.findRelayCheckins(segment.id())) {
                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("escortId", c.escortId());
                cv.put("role", c.role().name());
                cv.put("checkTime", c.checkTime().toString());
                checkinViews.add(cv);
            }
            sv.put("checkins", checkinViews);
            segmentViews.add(sv);
        }
        body.put("relaySegments", segmentViews);

        // 人员资格预约视图：班段及资格版本证据查询。
        List<Map<String, Object>> reservationViews = new ArrayList<>();
        for (EscortReservation reservation : repository.findReservationsByShipment(shipment.id())) {
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("reservationId", reservation.id());
            rv.put("segmentId", reservation.segmentId());
            rv.put("escortId", reservation.escortId());
            rv.put("shiftStartUtc", reservation.shiftStartUtc().toString());
            rv.put("shiftEndUtc", reservation.shiftEndUtc().toString());
            rv.put("status", reservation.status().name());
            rv.put("versionNo", reservation.versionNo());
            rv.put("qualificationId", reservation.qualificationId());
            rv.put("qualificationVersion", reservation.qualificationVersion());
            reservationViews.add(rv);
        }
        body.put("reservations", reservationViews);

        if (shipment.currentApprovalId() != null) {
            RouteApproval current = currentApproval(shipment);
            List<Map<String, Object>> curfewViews = new ArrayList<>();
            for (NightCurfew curfew : repository.findNightCurfews(current.routeCode())) {
                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("curfewStart", curfew.curfewStart().toString());
                cv.put("curfewEnd", curfew.curfewEnd().toString());
                curfewViews.add(cv);
            }
            body.put("nightCurfews", curfewViews);
            NightCurfew applicable = applicableCurfew(shipment, repository.findWaypoints(current.id()));
            body.put("relayRequired", applicable != null);
            body.put("curfewStart", applicable == null ? null : applicable.curfewStart().toString());
        }

        List<Map<String, Object>> disposalViews = new ArrayList<>();
        for (DisposalRecord d : repository.findDisposals(shipment.id())) {
            Map<String, Object> dv = new LinkedHashMap<>();
            dv.put("segmentId", d.segmentId());
            dv.put("reason", d.reason());
            dv.put("curfewStart", d.curfewStart().toString());
            dv.put("createdAt", d.createdAt().toString());
            disposalViews.add(dv);
        }
        body.put("disposals", disposalViews);
        return body;
    }

    // ============================ 幂等支撑 ============================

    /**
     * 以 requestId 幂等执行业务动作：
     * <ul>
     *   <li>同键同参：返回首次结果（成功或业务失败，含首次 HTTP 状态码）；</li>
     *   <li>同键异参：指纹不符返回 409；</li>
     *   <li>处理中并发：返回 409。</li>
     * </ul>
     */
    private ServiceResult withIdempotency(String requestId, String fingerprint,
                                          java.util.function.Supplier<ServiceResult> action) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<IdempotentRecord> existing = repository.findIdempotent(requestId);
            if (existing.isPresent()) {
                IdempotentRecord record = existing.get();
                if (!record.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENT_FINGERPRINT_MISMATCH",
                            "requestId=" + requestId + " 已用于不同业务参数");
                }
                if ("COMPLETED".equals(record.status())) {
                    Map<String, Object> stored = parseJson(record.responseBody());
                    // 非 2xx 首次结果以相同状态码/错误码重放，保持 HTTP 语义一致。
                    if (record.httpStatus() != null && record.httpStatus() >= 400) {
                        Object code = stored.get("errorCode");
                        if (code == null) {
                            code = stored.get("code");
                        }
                        throw new ApiException(org.springframework.http.HttpStatus.valueOf(record.httpStatus()),
                                code == null ? "IDEMPOTENT_REPLAY" : code.toString(),
                                stored.get("message") == null ? "" : stored.get("message").toString());
                    }
                    return new ServiceResult(record.httpStatus(), stored);
                }
                throw ApiException.conflict("IDEMPOTENT_PROCESSING",
                        "相同 requestId 请求正在处理中");
            }
            boolean inserted = repository.insertProcessingIdempotent(requestId, fingerprint, now());
            if (!inserted) {
                continue; // 并发插入竞争：重读记录走重放分支
            }
            try {
                ServiceResult result = action.get();
                repository.completeIdempotent(requestId, result.httpStatus(), toJson(result.body()));
                return result;
            } catch (ApiException e) {
                Map<String, Object> errorBody = Map.of(
                        "code", e.getCode(),
                        "message", e.getMessage() == null ? "" : e.getMessage());
                repository.completeIdempotent(requestId, e.getStatus().value(), toJson(errorBody));
                throw e;
            } catch (RuntimeException e) {
                // 系统异常：保留 PROCESSING 之外，直接抛出（占位不删除，便于排查）。
                throw e;
            }
        }
        throw ApiException.conflict("IDEMPOTENT_RACE", "幂等记录并发竞争，请稍后重试");
    }

    /**
     * 人员资格/预约专用幂等：业务写入与幂等结果在同一事务提交（原子一致）；
     * 同键同参重放首次成功响应，同键异参 409；业务失败删除占位（失败不占键），允许同键重试。
     */
    private ServiceResult withIdempotencyAtomic(String requestId, String fingerprint,
                                                java.util.function.Supplier<ServiceResult> action) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<IdempotentRecord> existing = repository.findIdempotent(requestId);
            if (existing.isPresent()) {
                IdempotentRecord record = existing.get();
                if (!record.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENT_FINGERPRINT_MISMATCH",
                            "requestId=" + requestId + " 已用于不同业务参数");
                }
                if ("COMPLETED".equals(record.status())) {
                    return new ServiceResult(record.httpStatus(), parseJson(record.responseBody()));
                }
                throw ApiException.conflict("IDEMPOTENT_PROCESSING",
                        "相同 requestId 请求正在处理中");
            }
            boolean inserted = repository.insertProcessingIdempotent(requestId, fingerprint, now());
            if (!inserted) {
                continue; // 并发插入竞争：重读记录走重放分支
            }
            try {
                return tx.execute(status -> {
                    ServiceResult result = action.get();
                    repository.completeIdempotent(requestId, result.httpStatus(), toJson(result.body()));
                    return result;
                });
            } catch (ApiException e) {
                // 失败不占键：业务事务已回滚，删除幂等占位，允许同键修正后重试。
                repository.deleteIdempotent(requestId);
                throw e;
            }
        }
        throw ApiException.conflict("IDEMPOTENT_RACE", "幂等记录并发竞争，请稍后重试");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static String fingerprint(String operation, Object... parts) {
        StringBuilder sb = new StringBuilder(operation);
        for (Object part : parts) {
            sb.append('|').append(part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
