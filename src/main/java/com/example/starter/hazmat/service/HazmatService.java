package com.example.starter.hazmat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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
import com.example.starter.hazmat.domain.RelayStatus;
import com.example.starter.hazmat.domain.RouteApprovalStatus;
import com.example.starter.hazmat.domain.ShipmentStatus;
import com.example.starter.hazmat.domain.WaypointState;
import com.example.starter.hazmat.dto.ApplyRequest;
import com.example.starter.hazmat.dto.CheckinRequest;
import com.example.starter.hazmat.dto.RelayCheckinRequest;
import com.example.starter.hazmat.dto.RelayRequest;
import com.example.starter.hazmat.dto.RerouteRequest;
import com.example.starter.hazmat.po.ApprovalWaypoint;
import com.example.starter.hazmat.po.Checkin;
import com.example.starter.hazmat.po.DisposalRecord;
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
    public static final String EVT_RELAY_CHECKIN_REVERSED = "RELAY_CHECKIN_REVERSED";
    public static final String EVT_RELAY_SHIFT_COMPLETED = "RELAY_SHIFT_COMPLETED";
    public static final String EVT_CURFEW_FROZEN = "CURFEW_FROZEN";

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
        // 夜间禁行：计划到达禁行起点的运单，发运前必须已设置安全接力点。
        RouteApproval approval = currentApproval(shipment);
        LocalDateTime curfewStart = applicableCurfewStart(approval.routeCode(),
                repository.findWaypoints(approval.id()));
        if (curfewStart != null && repository.findRelaysByShipment(shipment.id()).isEmpty()) {
            throw ApiException.conflict("CURFEW_RELAY_REQUIRED",
                    "路线计划到达夜间禁行起点 " + curfewStart + "，须先设置安全接力点方可发运");
        }
        repository.updateStatus(shipment.id(), ShipmentStatus.DISPATCHED);
        return ServiceResult.ok(shipmentView(shipmentNo));
    }

    // ============================ 夜间接力押运 ============================

    public ServiceResult createRelay(String shipmentNo, RelayRequest request) {
        String fingerprint = fingerprint("RELAY_CREATE", request.requestId(), shipmentNo,
                String.valueOf(request.waypointSeq()), request.outgoingEscort(), request.incomingEscort());
        return withIdempotency(request.requestId(), fingerprint,
                () -> tx.execute(status -> doCreateRelay(shipmentNo, request)));
    }

    private ServiceResult doCreateRelay(String shipmentNo, RelayRequest request) {
        Shipment shipment = loadShipment(shipmentNo);
        if (shipment.status().isTerminal()) {
            throw ApiException.conflict("SHIPMENT_TERMINAL",
                    "运单终态 " + shipment.status() + "，不可设置接力点");
        }
        if (shipment.status() == ShipmentStatus.APPLIED) {
            throw ApiException.conflict("INVALID_STATUS", "路线核准通过后方可设置安全接力点");
        }
        if (request.outgoingEscort().equals(request.incomingEscort())) {
            throw ApiException.badRequest("ESCORT_SAME", "交班与接班押运员编号不得相同");
        }
        RouteApproval approval = currentApproval(shipment);
        List<ApprovalWaypoint> waypoints = repository.findWaypoints(approval.id());
        ApprovalWaypoint target = waypoints.stream()
                .filter(w -> w.seq() == request.waypointSeq()).findFirst()
                .orElseThrow(() -> ApiException.badRequest("WAYPOINT_NOT_FOUND",
                        "当前路线无途经点序号 " + request.waypointSeq()));
        LocalDateTime curfewStart = applicableCurfewStart(approval.routeCode(), waypoints);
        if (curfewStart == null) {
            throw ApiException.badRequest("CURFEW_NOT_APPLICABLE",
                    "路线 " + approval.routeCode() + " 计划不触及夜间禁行时段，无需设置接力点");
        }
        if (!target.plannedTime().isBefore(curfewStart)) {
            throw ApiException.badRequest("RELAY_POINT_IN_CURFEW",
                    "接力点计划通过时刻 " + target.plannedTime() + " 不早于夜间禁行起点 " + curfewStart
                            + "，须选择禁行起点前的安全接力点");
        }
        LocalDateTime now = now();
        long relayId = repository.insertRelaySegment(shipment.id(), approval.id(), target.seq(),
                request.outgoingEscort(), request.incomingEscort(), curfewStart, request.requestId(), now);
        try {
            // 主键冲突即并发占用失败：同一运单同时刻只允许一个进行中接力段。
            repository.occupyRelay(shipment.id(), relayId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("RELAY_IN_PROGRESS",
                    "运单已存在进行中接力段，同时刻只允许一个");
        }
        Map<String, Object> body = shipmentView(shipmentNo);
        body.put("relayId", relayId);
        body.put("message", "安全接力点已设置，须在禁行起点 " + curfewStart + " 前完成双方打卡换班");
        return ServiceResult.ok(body);
    }

    public ServiceResult relayCheckin(String shipmentNo, long relayId, RelayCheckinRequest request) {
        String fingerprint = fingerprint("RELAY_CHECKIN", request.requestId(), shipmentNo,
                String.valueOf(relayId), request.escortNo(), request.checkTime().toString());
        return withIdempotency(request.requestId(), fingerprint,
                () -> tx.execute(status -> doRelayCheckin(shipmentNo, relayId, request)));
    }

    private ServiceResult doRelayCheckin(String shipmentNo, long relayId, RelayCheckinRequest request) {
        Shipment shipment = loadShipment(shipmentNo);
        LocalDateTime now = now();
        if (shipment.status().isTerminal()) {
            // 禁行冻结/失联/结案均为终态：冻结后不再接受新打卡。
            throw ApiException.conflict("SHIPMENT_TERMINAL",
                    "运单终态 " + shipment.status() + "，接力打卡已冻结");
        }
        RelaySegment relay = repository.findRelayById(relayId)
                .orElseThrow(() -> ApiException.notFound("RELAY_NOT_FOUND", "接力段不存在: " + relayId));
        if (relay.shipmentId() != shipment.id()) {
            throw ApiException.badRequest("RELAY_MISMATCH", "接力段不属于该运单");
        }
        if (relay.status() != RelayStatus.IN_PROGRESS) {
            throw ApiException.conflict("RELAY_NOT_IN_PROGRESS",
                    "接力段当前状态 " + relay.status() + "，不可打卡");
        }
        boolean outgoing = request.escortNo().equals(relay.outgoingEscort());
        if (!outgoing && !request.escortNo().equals(relay.incomingEscort())) {
            throw ApiException.badRequest("ESCORT_NOT_IN_SEGMENT",
                    "押运员 " + request.escortNo() + " 不是该接力段交班/接班押运员");
        }
        Optional<RelayCheckin> duplicate = repository.findRelayCheckin(
                shipment.id(), relay.waypointSeq(), request.escortNo());
        if (duplicate.isPresent()) {
            throw ApiException.conflict("RELAY_ALREADY_CHECKED",
                    "押运员 " + request.escortNo() + " 已在接力点 " + relay.waypointSeq()
                            + " 打卡（运单+途经点序号去重），首次打卡时刻 " + duplicate.get().checkTime());
        }
        if (!request.checkTime().isBefore(relay.deadline())) {
            // 恰满禁行起点判定：打卡时刻 >= 换班截止即未按时换班，冻结与处置登记在同一事务内提交。
            freezeForCurfew(shipment, relay, now);
            Map<String, Object> body = shipmentView(shipmentNo);
            body.put("errorCode", "CURFEW_REACHED");
            body.put("message", "打卡时刻 " + request.checkTime() + " 已到达夜间禁行起点 " + relay.deadline()
                    + "，换班未按时完成，运单冻结并转处置");
            return ServiceResult.status(409, body);
        }
        // 时序校验：接班时刻不得早于交班时刻；乱序回传按时刻归位，逆序拒绝并告警。
        if (outgoing && relay.incomingTime() != null && relay.incomingTime().isBefore(request.checkTime())) {
            rejectRelayReverse(shipment, relay, request.checkTime(),
                    "接班打卡时刻 " + relay.incomingTime() + " 早于交班打卡时刻 " + request.checkTime(), now);
        }
        if (!outgoing && relay.outgoingTime() != null && request.checkTime().isBefore(relay.outgoingTime())) {
            rejectRelayReverse(shipment, relay, request.checkTime(),
                    "接班打卡时刻 " + request.checkTime() + " 早于交班打卡时刻 " + relay.outgoingTime(), now);
        }
        repository.insertRelayCheckin(relay.id(), shipment.id(), relay.waypointSeq(),
                request.escortNo(), request.checkTime(), request.requestId(), now);
        repository.updateRelayEscortTime(relay.id(), outgoing, request.checkTime());
        LocalDateTime outgoingTime = outgoing ? request.checkTime() : relay.outgoingTime();
        LocalDateTime incomingTime = outgoing ? relay.incomingTime() : request.checkTime();
        if (outgoingTime != null && incomingTime != null) {
            // 双方打卡齐全换班才生效（单人打卡不生效）；生效时刻=接班打卡时刻。
            repository.completeRelay(relay.id(), incomingTime);
            repository.releaseRelay(shipment.id());
            repository.insertEvent(shipment.id(), EVT_RELAY_SHIFT_COMPLETED,
                    "接力点 " + relay.waypointSeq() + " 换班完成：交班 " + relay.outgoingEscort()
                            + " @" + outgoingTime + "，接班 " + relay.incomingEscort() + " @" + incomingTime, now);
        }
        Map<String, Object> body = shipmentView(shipmentNo);
        body.put("relayId", relay.id());
        return ServiceResult.ok(body);
    }

    private void rejectRelayReverse(Shipment shipment, RelaySegment relay, LocalDateTime checkTime,
                                    String detail, LocalDateTime now) {
        String message = "接力点 " + relay.waypointSeq() + " 打卡时刻逆序：" + detail + "，拒绝并告警";
        eventRecorder.recordNewTransaction(shipment.id(), EVT_RELAY_CHECKIN_REVERSED, message, now);
        throw ApiException.badRequest("RELAY_CHECKIN_REVERSED", message);
    }

    /**
     * 夜间禁行冻结：未在禁行起点前完成换班的运单，终态 CAS 保证与失联/核销竞争唯一终态；
     * 冻结成功时接力段冻结、释放占用、处置登记与事件在同一事务内完成。
     *
     * @return true 表示本次调用完成冻结；false 表示他方已先终态化
     */
    private boolean freezeForCurfew(Shipment shipment, RelaySegment relay, LocalDateTime now) {
        int rows = repository.compareAndSetTerminal(shipment.id(), ShipmentStatus.CURFEW_FROZEN);
        if (rows == 0) {
            return false;
        }
        repository.freezeRelay(relay.id());
        repository.releaseRelay(shipment.id());
        repository.insertDisposal(shipment.id(), relay.id(),
                "未在夜间禁行起点 " + relay.deadline() + " 前完成押运换班，运单冻结并转处置", now);
        repository.insertEvent(shipment.id(), EVT_CURFEW_FROZEN,
                "接力段 " + relay.id() + " 恰满禁行起点 " + relay.deadline() + " 仍未完成换班，冻结新打卡并转处置", now);
        return true;
    }

    /**
     * 计算路线计划触及的夜间禁行起点：任一途经点计划通过时刻落入 [curfewStart, curfewEnd)
     * （恰等于起点即触及），返回最早禁行起点；不触及返回 null。
     */
    private LocalDateTime applicableCurfewStart(String routeCode, List<ApprovalWaypoint> waypoints) {
        LocalDateTime result = null;
        for (NightCurfew curfew : repository.findNightCurfews(routeCode)) {
            boolean hit = waypoints.stream().anyMatch(w ->
                    !w.plannedTime().isBefore(curfew.curfewStart()) && w.plannedTime().isBefore(curfew.curfewEnd()));
            if (hit && (result == null || curfew.curfewStart().isBefore(result))) {
                result = curfew.curfewStart();
            }
        }
        return result;
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
            throw ApiException.conflict("ALREADY_CURFEW_FROZEN", "运单已夜间禁行冻结，不可核销");
        }
        // 条件更新：与失联升级/禁行冻结竞争，败者拿不到终态。
        int rows = repository.compareAndSetTerminal(shipment.id(), ShipmentStatus.COMPLETED);
        if (rows == 0) {
            Shipment current = repository.findShipmentById(shipment.id()).orElseThrow();
            if (current.status() == ShipmentStatus.COMPLETED) {
                return ServiceResult.ok(shipmentView(shipmentNo));
            }
            if (current.status() == ShipmentStatus.CURFEW_FROZEN) {
                throw ApiException.conflict("ALREADY_CURFEW_FROZEN", "运单已夜间禁行冻结，不可核销");
            }
            throw ApiException.conflict("ALREADY_LOST_CONTACT", "运单已失联冻结，不可核销");
        }
        repository.releaseVehicle(shipment.vehiclePlate());
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

    /** 扫描单个运单：标记超时点；连续 2 点超时则升级失联并冻结（唯一终态 CAS）。 */
    private Map<String, Object> scanOne(long shipmentId, LocalDateTime now) {
        Shipment shipment = repository.findShipmentById(shipmentId).orElseThrow();
        if (shipment.status().isTerminal()) {
            return Map.of();
        }
        // 夜间禁行冻结优先：恰满禁行起点未完成换班即终态化，冻结后不再标记超时计程（唯一终态）。
        Optional<Long> activeRelayId = repository.findActiveRelay(shipmentId);
        if (activeRelayId.isPresent()) {
            RelaySegment relay = repository.findRelayById(activeRelayId.get()).orElse(null);
            if (relay != null && relay.status() == RelayStatus.IN_PROGRESS
                    && !now.isBefore(relay.deadline())) {
                boolean frozen = freezeForCurfew(shipment, relay, now);
                if (!frozen) {
                    return Map.of(); // 终态竞争败者：他方已终态化
                }
                Map<String, Object> changed = new LinkedHashMap<>();
                changed.put("changed", true);
                changed.put("shipmentNo", shipment.shipmentNo());
                changed.put("newlyTimedOut", List.of());
                changed.put("lostContact", false);
                changed.put("curfewFrozen", true);
                return changed;
            }
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
        boolean lost = hasConsecutiveTimeouts(repository.findWaypoints(approvalId));
        boolean upgraded = false;
        if (lost) {
            int rows = repository.compareAndSetTerminal(shipmentId, ShipmentStatus.LOST_CONTACT);
            if (rows == 1) {
                upgraded = true;
                repository.freezeVehicle(shipment.vehiclePlate(), shipmentId, now);
                repository.releaseVehicle(shipment.vehiclePlate());
                repository.insertEvent(shipmentId, EVT_LOST_CONTACT,
                        "连续 2 个途经点超时未打卡，升级失联并冻结车辆 " + shipment.vehiclePlate(), now);
            }
        }
        if (timedOut.isEmpty() && !upgraded) {
            return Map.of();
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("changed", true);
        changed.put("shipmentNo", shipment.shipmentNo());
        changed.put("newlyTimedOut", timedOut);
        changed.put("lostContact", upgraded);
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

        List<Map<String, Object>> relayViews = new ArrayList<>();
        for (RelaySegment relay : repository.findRelaysByShipment(shipment.id())) {
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("relayId", relay.id());
            rv.put("waypointSeq", relay.waypointSeq());
            rv.put("status", relay.status().name());
            rv.put("outgoingEscort", relay.outgoingEscort());
            rv.put("incomingEscort", relay.incomingEscort());
            rv.put("deadline", relay.deadline().toString());
            rv.put("outgoingTime", relay.outgoingTime() == null ? null : relay.outgoingTime().toString());
            rv.put("incomingTime", relay.incomingTime() == null ? null : relay.incomingTime().toString());
            rv.put("completedAt", relay.completedAt() == null ? null : relay.completedAt().toString());
            List<Map<String, Object>> rcViews = new ArrayList<>();
            for (RelayCheckin rc : repository.findRelayCheckins(relay.id())) {
                Map<String, Object> rcv = new LinkedHashMap<>();
                rcv.put("escortNo", rc.escortNo());
                rcv.put("checkTime", rc.checkTime().toString());
                rcv.put("requestId", rc.requestId());
                rcViews.add(rcv);
            }
            rv.put("checkins", rcViews);
            relayViews.add(rv);
        }
        body.put("relays", relayViews);

        List<Map<String, Object>> disposalViews = new ArrayList<>();
        for (DisposalRecord d : repository.findDisposals(shipment.id())) {
            Map<String, Object> dv = new LinkedHashMap<>();
            dv.put("relayId", d.relayId());
            dv.put("reason", d.reason());
            dv.put("createdAt", d.createdAt().toString());
            disposalViews.add(dv);
        }
        body.put("disposals", disposalViews);

        List<Map<String, Object>> eventViews = new ArrayList<>();
        for (ShipmentEvent e : repository.findEvents(shipment.id())) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("eventType", e.eventType());
            ev.put("detail", e.detail());
            ev.put("eventTime", e.eventTime().toString());
            eventViews.add(ev);
        }
        body.put("events", eventViews);
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
