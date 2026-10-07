package com.example.starter.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.example.starter.dto.GrainDtos.ApplyFumigationRequest;
import com.example.starter.dto.GrainDtos.ApproveFumigationRequest;
import com.example.starter.dto.GrainDtos.BeginAerationRequest;
import com.example.starter.dto.GrainDtos.AddPointsRequest;
import com.example.starter.dto.GrainDtos.FrameChainView;
import com.example.starter.dto.GrainDtos.FramePointReading;
import com.example.starter.dto.GrainDtos.FrameReadingView;
import com.example.starter.dto.GrainDtos.FumigationView;
import com.example.starter.dto.GrainDtos.GasFrameRequest;
import com.example.starter.dto.GrainDtos.GasFrameView;
import com.example.starter.dto.GrainDtos.GasReadingRequest;
import com.example.starter.dto.GrainDtos.GasReadingView;
import com.example.starter.dto.GrainDtos.PointListView;
import com.example.starter.dto.GrainDtos.RegisterPointListRequest;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.dto.GrainDtos.StartFumigationRequest;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;
import com.example.starter.util.Fingerprints;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 熏蒸作业服务：申请（UTC 作业窗、与普通通风窗半开互斥）、两名不同审批人双审批、
 * 实际开始原子锁仓并冻结方案版本、结束转散气原子占用空闲风机、合成气体采样
 * （单号内序号唯一、时刻严格递增）、两名不同复核人重查最新读数与版本后原子解除。
 *
 * <p>多测点受控单（题03）：散气前登记 2~10 个测点并冻结名单版本 1，散气中只能增点不能删点，
 * 每增补一批名单版本与单据版本各加 1，旧版帧不得与新版帧拼成连续三帧；采样以整帧提交，
 * 帧含递增序号、全帧共用的 UTC 时刻与全名单有限非负浓度，缺点/重复点/未知点/非递增时刻整帧拒绝，
 * 不写部分读数；仅当前名单版本下连续三帧全点 ≤ 阈值（恰等允许）且首末间隔 ≥30 分钟，
 * 两名不同复核人才能按最新帧与名单版本解除。受控单拒绝旧单点采样入口，解除入口仍检查全点条件；
 * 未启用多测点的旧单完全保持旧行为。
 *
 * <p>版本从 1 开始，每次成功变更加 1；写操作均带 requestId 幂等，
 * 指纹为 requestId 与全部业务参数（含版本、集合、精确时刻、全部测点与浓度）的结构化 SHA-256，
 * 同键同参重放首次冻结结果，同键异参 409，业务失败回滚不占键。
 */
@Service
public class FumigationService {

    /** 解除所需连续安全点数。 */
    static final int SAFE_RUN_LENGTH = 3;
    /** 连续安全三点首末最小间隔，恰等合法。 */
    static final Duration MIN_FIRST_LAST_GAP = Duration.ofMinutes(30);
    /** 多测点名单初始登记最少测点个数。 */
    static final int MIN_POINTS = 2;
    /** 多测点名单最多测点个数。 */
    static final int MAX_POINTS = 10;

    private final JdbcTemplate jdbc;
    private final SiloService siloService;

    public FumigationService(JdbcTemplate jdbc, SiloService siloService) {
        this.jdbc = jdbc;
        this.siloService = siloService;
    }

    /**
     * 申请熏蒸单：绑定粮仓与 UTC 半开作业窗；同仓作业窗与普通通风窗及未终结熏蒸窗
     * 半开互斥（端点相接可共存）；同仓只允许一张未终结熏蒸单。
     */
    @Transactional
    public FumigationView apply(long siloId, ApplyFumigationRequest request) {
        if (!request.windowStart().isBefore(request.windowEnd())) {
            throw ApiException.badRequest("INVALID_WINDOW", "作业窗起点必须早于终点");
        }
        if (request.safetyThreshold().signum() < 0) {
            throw ApiException.badRequest("INVALID_THRESHOLD", "合成安全阈值必须为非负小数");
        }
        String fingerprint = Fingerprints.structured(request.requestId(), siloId,
                request.windowStart(), request.windowEnd(), request.syntheticAgent(),
                request.safetyThreshold().stripTrailingZeros().toPlainString());

        Optional<OrderRow> existing = findOrderByColumn("request_id", request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        // 锁定粮仓行，串行化同仓并发申请
        lockSilo(siloId);

        Optional<OrderRow> rechecked = findOrderByColumn("request_id", request.requestId());
        if (rechecked.isPresent()) {
            return replayOrConflict(rechecked.get(), fingerprint);
        }

        SiloStatus siloStatus = currentSiloStatus(siloId);
        if (siloStatus == SiloStatus.ARCHIVED) {
            throw ApiException.conflict("SILO_ARCHIVED", "粮仓已归档，禁止申请熏蒸");
        }
        if (siloStatus == SiloStatus.MANUAL_HANDLING) {
            throw ApiException.unprocessable("MANUAL_HANDLING_REQUIRED", "粮仓已转人工处置，禁止申请熏蒸");
        }
        if (siloStatus == SiloStatus.FUMIGATING || siloStatus == SiloStatus.AERATING) {
            throw ApiException.conflict("FUMIGATION_ALREADY_ACTIVE", "粮仓熏蒸锁仓中，禁止再申请熏蒸");
        }

        assertNoWindowOverlap(siloId, request.windowStart(), request.windowEnd());

        long orderId;
        try {
            orderId = siloService.insert(
                    "INSERT INTO fumigation_order (silo_id, request_id, order_no, window_start, window_end,"
                            + " synthetic_agent, plan_version, safety_threshold, status, fingerprint, version,"
                            + " created_at)"
                            + " VALUES (?, ?, NULL, ?, ?, ?, 1, ?, 'PENDING_APPROVAL', ?, 1, ?)",
                    siloId, request.requestId(), utc(request.windowStart()),
                    utc(request.windowEnd()), request.syntheticAgent(),
                    request.safetyThreshold(), fingerprint, utc(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            OrderRow row = findOrderByColumn("request_id", request.requestId()).orElseThrow(() -> duplicate);
            return replayOrConflict(row, fingerprint);
        }
        String orderNo = String.format("FUM-%08d", orderId);
        jdbc.update("UPDATE fumigation_order SET order_no = ? WHERE id = ?", orderNo, orderId);
        return toView(findOrderById(orderId).orElseThrow(), List.of(), false);
    }

    /**
     * 熏蒸审批：两名不同审批人各批准一次；expectedVersion 乐观锁，
     * 第二名审批后状态转 APPROVED；同一审批人重复批准业务拒绝（失败不占键）。
     */
    @Transactional
    public FumigationView approve(long orderId, ApproveFumigationRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), request.approver());

        Optional<ApprovalRow> existingApproval = findApprovalByRequestId(request.requestId());
        if (existingApproval.isPresent()) {
            ApprovalRow row = existingApproval.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toView(findOrderById(orderId).orElseThrow(
                    () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId)),
                    findApprovers(orderId), true);
        }

        OrderRow order = lockOrder(orderId);
        Optional<ApprovalRow> recheckedApproval = findApprovalByRequestId(request.requestId());
        if (recheckedApproval.isPresent()) {
            ApprovalRow row = recheckedApproval.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toView(order, findApprovers(orderId), true);
        }
        if (order.status() != FumigationStatus.PENDING_APPROVAL) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅待审批熏蒸单可审批，当前: " + order.status());
        }
        if (order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }

        try {
            siloService.insert(
                    "INSERT INTO fumigation_approval (fumigation_id, approver, request_id, expected_version,"
                            + " fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    orderId, request.approver(), request.requestId(),
                    request.expectedVersion(), fingerprint, utc(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            // 同一审批人重复批准属于业务拒绝；事务回滚，requestId 不占键
            throw ApiException.unprocessable("APPROVER_DUPLICATED",
                    "同一审批人不可重复批准: " + request.approver());
        }

        List<String> approvers = findApprovers(orderId);
        FumigationStatus newStatus = approvers.size() >= 2
                ? FumigationStatus.APPROVED : FumigationStatus.PENDING_APPROVAL;
        jdbc.update("UPDATE fumigation_order SET status = ?, version = version + 1 WHERE id = ?",
                newStatus.name(), orderId);
        return toView(findOrderById(orderId).orElseThrow(), approvers, false);
    }

    /**
     * 实际开始：持仓锁后再查无执行中普通通风；开始时刻须落在 UTC 作业窗内；
     * 原子锁仓（粮仓转 FUMIGATING）、冻结方案版本，禁止人员进入与普通通风。
     */
    @Transactional
    public FumigationView start(long orderId, StartFumigationRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), request.startedAt());

        OrderRow order = lockOrder(orderId);
        if (order.startRequestId() != null) {
            return replayAction(order, order.startRequestId(), order.startFingerprint(),
                    fingerprint, request.requestId());
        }
        if (order.status() != FumigationStatus.APPROVED) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅已双审批熏蒸单可开始，当前: " + order.status());
        }
        if (order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }
        if (request.startedAt().isBefore(order.windowStart())
                || !request.startedAt().isBefore(order.windowEnd())) {
            throw ApiException.unprocessable("START_OUTSIDE_WINDOW", "实际开始时刻必须位于作业窗 [起点, 终点) 内");
        }

        // 锁仓行并再查执行中普通通风：熏蒸与通风不得各自放行
        List<SiloStatus> rows = jdbc.query("SELECT status FROM silo WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> SiloStatus.valueOf(rs.getString("status")), order.siloId());
        if (rows.isEmpty()) {
            throw ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + order.siloId());
        }
        SiloStatus siloStatus = rows.get(0);
        if (siloStatus == SiloStatus.FUMIGATING || siloStatus == SiloStatus.AERATING) {
            throw ApiException.unprocessable("FUMIGATION_ALREADY_ACTIVE", "粮仓已被另一熏蒸作业锁定，禁止开始");
        }
        if (siloStatus == SiloStatus.ARCHIVED) {
            throw ApiException.unprocessable("SILO_ARCHIVED", "粮仓已归档，禁止开始熏蒸");
        }
        Integer executingVentilation = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND status = 'EXECUTING'",
                Integer.class, order.siloId());
        if (executingVentilation != null && executingVentilation > 0) {
            throw ApiException.unprocessable("VENTILATION_EXECUTING", "存在执行中的普通通风，禁止开始熏蒸");
        }

        jdbc.update("UPDATE fumigation_order SET status = 'FUMIGATING', frozen_plan_version = plan_version,"
                        + " started_at = ?, start_request_id = ?, start_fingerprint = ?, version = version + 1"
                        + " WHERE id = ?",
                utc(request.startedAt()), request.requestId(), fingerprint, orderId);
        jdbc.update("UPDATE silo SET status = 'FUMIGATING' WHERE id = ?", order.siloId());
        return toView(findOrderById(orderId).orElseThrow(), findApprovers(orderId), false);
    }

    /**
     * 结束熏蒸转散气：原子绑定一台空闲风机（沿用风机唯一资源规则，占用失败整体回滚），
     * 粮仓与熏蒸单转散气态。
     */
    @Transactional
    public FumigationView beginAeration(long orderId, BeginAerationRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), request.fanId(), request.aerationStartedAt());

        OrderRow order = lockOrder(orderId);
        if (order.aerationRequestId() != null) {
            return replayAction(order, order.aerationRequestId(), order.aerationFingerprint(),
                    fingerprint, request.requestId());
        }
        if (order.status() != FumigationStatus.FUMIGATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅熏蒸中作业单可转散气，当前: " + order.status());
        }
        if (order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }
        if (request.aerationStartedAt().isBefore(order.startedAt())) {
            throw ApiException.unprocessable("INVALID_AERATION_TIME", "散气开始时刻不得早于熏蒸开始时刻");
        }
        siloService.getFan(request.fanId());

        int locked = jdbc.update(
                "UPDATE fan SET status = 'LOCKED', locked_fumigation_id = ?"
                        + " WHERE id = ? AND status = 'IDLE' AND locked_job_id IS NULL"
                        + " AND locked_fumigation_id IS NULL",
                orderId, request.fanId());
        if (locked == 0) {
            throw ApiException.conflict("FAN_BUSY", "风机已被占用: " + request.fanId());
        }

        jdbc.update("UPDATE fumigation_order SET status = 'AERATING', fan_id = ?,"
                        + " aeration_started_at = ?, aeration_request_id = ?, aeration_fingerprint = ?,"
                        + " version = version + 1 WHERE id = ?",
                request.fanId(), utc(request.aerationStartedAt()), request.requestId(),
                fingerprint, orderId);
        jdbc.update("UPDATE silo SET status = 'AERATING' WHERE id = ?", order.siloId());
        return toView(findOrderById(orderId).orElseThrow(), findApprovers(orderId), false);
    }

    // ============================ 多测点名单与采样帧 ============================

    /**
     * 散气前登记测点名单：2~10 个互不相同测点；成功后该单成为受控多测点单并冻结名单版本 1，
     * 熏蒸单乐观锁版本 +1。仅熏蒸中（尚未转散气）可登记，且只能登记一次。
     */
    @Transactional
    public PointListView registerPointList(long orderId, RegisterPointListRequest request) {
        List<String> points = normalizeDistinctPoints(request.points(), "REGISTER");
        if (points.size() < MIN_POINTS) {
            throw ApiException.badRequest("INVALID_POINT_LIST", "测点名单至少包含 " + MIN_POINTS + " 个不同测点");
        }
        String fingerprint = pointOpFingerprint(orderId, "REGISTER", request.requestId(),
                request.expectedVersion(), points);

        Optional<PointOpRow> existing = findPointOpByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayPointOp(orderId, existing.get(), fingerprint);
        }

        OrderRow order = lockOrder(orderId);
        Optional<PointOpRow> rechecked = findPointOpByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayPointOp(orderId, rechecked.get(), fingerprint);
        }
        if (order.multiPoint()) {
            throw ApiException.unprocessable("POINT_LIST_ALREADY_REGISTERED", "测点名单已登记冻结，不可重复登记");
        }
        if (order.status() != FumigationStatus.FUMIGATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "测点名单须在熏蒸中、转散气前登记，当前: " + order.status());
        }
        if (order.version() != request.expectedVersion()) {
            throw versionConflict(request.expectedVersion(), order.version());
        }

        try {
            siloService.insert(
                    "INSERT INTO gas_point_op (fumigation_id, request_id, op_type, expected_version,"
                            + " list_version, fingerprint, created_at) VALUES (?, ?, 'REGISTER', ?, 1, ?, ?)",
                    orderId, request.requestId(), request.expectedVersion(), fingerprint, utc(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            PointOpRow row = findPointOpByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            return replayPointOp(orderId, row, fingerprint);
        }
        for (String point : points) {
            jdbc.update("INSERT INTO gas_point (fumigation_id, point_code, list_version, request_id, created_at)"
                    + " VALUES (?, ?, 1, ?, ?)", orderId, point, request.requestId(), utc(Instant.now()));
        }
        jdbc.update("UPDATE fumigation_order SET multi_point = TRUE, point_list_version = 1,"
                + " version = version + 1 WHERE id = ?", orderId);
        return new PointListView(1, points, false);
    }

    /**
     * 散气中增补测点：只增不删（与既有测点重复即拒绝，失败不占键），增补后总数不超过 10；
     * 成功名单版本 +1、熏蒸单版本 +1。增点后待解除确认失效：旧版帧不得与新版帧拼成连续三帧，
     * 必须从新版首个完整帧重新累计，旧帧与人员意见保留。
     */
    @Transactional
    public PointListView addPoints(long orderId, AddPointsRequest request) {
        List<String> newPoints = normalizeDistinctPoints(request.points(), "ADD");
        String fingerprint = pointOpFingerprint(orderId, "ADD", request.requestId(),
                request.expectedVersion(), newPoints);

        Optional<PointOpRow> existing = findPointOpByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayPointOp(orderId, existing.get(), fingerprint);
        }

        OrderRow order = lockOrder(orderId);
        Optional<PointOpRow> rechecked = findPointOpByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayPointOp(orderId, rechecked.get(), fingerprint);
        }
        if (!order.multiPoint() || order.pointListVersion() == null) {
            throw ApiException.unprocessable("POINT_LIST_NOT_REGISTERED", "受控单尚未登记测点名单，不能增补测点");
        }
        if (order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气中可增补测点，当前: " + order.status());
        }
        if (order.version() != request.expectedVersion()) {
            throw versionConflict(request.expectedVersion(), order.version());
        }

        List<String> currentPoints = listPoints(orderId);
        for (String point : newPoints) {
            if (currentPoints.contains(point)) {
                throw ApiException.unprocessable("POINT_ALREADY_EXISTS",
                        "测点已在名单中，名单只增不删但不可重复登记: " + point);
            }
        }
        if (currentPoints.size() + newPoints.size() > MAX_POINTS) {
            throw ApiException.unprocessable("TOO_MANY_POINTS",
                    "增补后测点总数不得超过 " + MAX_POINTS + "，当前已有 " + currentPoints.size());
        }

        int newListVersion = order.pointListVersion() + 1;
        try {
            siloService.insert(
                    "INSERT INTO gas_point_op (fumigation_id, request_id, op_type, expected_version,"
                            + " list_version, fingerprint, created_at) VALUES (?, ?, 'ADD', ?, ?, ?, ?)",
                    orderId, request.requestId(), request.expectedVersion(), newListVersion,
                    fingerprint, utc(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            PointOpRow row = findPointOpByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            return replayPointOp(orderId, row, fingerprint);
        }
        for (String point : newPoints) {
            jdbc.update("INSERT INTO gas_point (fumigation_id, point_code, list_version, request_id, created_at)"
                    + " VALUES (?, ?, ?, ?, ?)", orderId, point, newListVersion,
                    request.requestId(), utc(Instant.now()));
        }
        jdbc.update("UPDATE fumigation_order SET point_list_version = ?, version = version + 1 WHERE id = ?",
                newListVersion, orderId);

        List<String> combined = new java.util.ArrayList<>(currentPoints);
        combined.addAll(newPoints);
        return new PointListView(newListVersion, List.copyOf(combined), false);
    }

    /** 查询当前测点名单（含各版本增补的全部测点）；未登记名单的单据返回 404。 */
    public PointListView getPointList(long orderId) {
        OrderRow order = findOrderById(orderId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId));
        if (!order.multiPoint() || order.pointListVersion() == null) {
            throw ApiException.notFound("POINT_LIST_NOT_FOUND", "该熏蒸单未登记多测点名单: " + orderId);
        }
        return new PointListView(order.pointListVersion(), listPoints(orderId), false);
    }

    /**
     * 多测点采样帧：帧内读数必须恰好覆盖采样时当前名单版本的全部测点（缺点、重复点、未知点整帧拒绝，
     * 不写部分读数）；各浓度有限非负；帧序号在单号内严格递增（重复拒绝，缺口留待打断连续计数）；
     * 帧采样时刻 UTC 且单号内严格递增（同帧各点共用同一时刻）。整帧与全部测点读数同事务写入或一起回滚；
     * 正常帧推动熏蒸单版本 +1，解除后的帧只记迟到（late=TRUE），不改版本与冻结证据。
     */
    @Transactional
    public GasFrameView ingestGasFrame(long orderId, GasFrameRequest request) {
        if (request.seqNo() < 1) {
            throw ApiException.badRequest("INVALID_SEQ_NO", "采样帧序号必须为正整数");
        }
        if (request.readings() == null || request.readings().isEmpty()) {
            throw ApiException.badRequest("INVALID_FRAME", "采样帧必须包含全部在册测点的读数");
        }
        // 帧内测点去重与浓度有限非负校验
        List<FramePointReading> ordered = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var reading : request.readings()) {
            if (reading == null || reading.pointCode() == null || reading.pointCode().isBlank()) {
                throw ApiException.badRequest("INVALID_POINT_CODE", "帧内测点编码不能为空");
            }
            if (reading.concentration() == null || reading.concentration().signum() < 0) {
                throw ApiException.badRequest("INVALID_CONCENTRATION",
                        "测点 " + (reading == null ? "" : reading.pointCode()) + " 浓度必须为有限非负小数");
            }
            if (!seen.add(reading.pointCode())) {
                throw ApiException.unprocessable("FRAME_POINT_DUPLICATED",
                        "同一采样帧内测点不可重复: " + reading.pointCode());
            }
            ordered.add(reading);
        }
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), request.seqNo(), request.measuredAt(),
                frameReadingsPayload(ordered));

        Optional<FrameRow> existing = findFrameByRequestId(request.requestId());
        if (existing.isPresent()) {
            FrameRow row = existing.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toFrameView(row, true);
        }

        OrderRow order = lockOrder(orderId);
        Optional<FrameRow> rechecked = findFrameByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            FrameRow row = rechecked.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toFrameView(row, true);
        }

        boolean late = order.status() == FumigationStatus.RELEASED;
        if (!late && order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气中熏蒸单可提交多测点采样帧，当前: " + order.status());
        }
        if (!order.multiPoint() || order.pointListVersion() == null) {
            throw ApiException.unprocessable("MULTI_POINT_NOT_ENABLED", "该熏蒸单未启用多测点，不能提交采样帧");
        }
        if (!late && order.version() != request.expectedVersion()) {
            throw versionConflict(request.expectedVersion(), order.version());
        }

        // 仅与采样时的当前名单版本比对：帧必须恰好覆盖全名单
        List<String> expectedPoints = listPoints(orderId);
        java.util.Map<String, BigDecimal> byPoint = new java.util.LinkedHashMap<>();
        for (FramePointReading reading : ordered) {
            byPoint.put(reading.pointCode(), reading.concentration());
        }
        for (String point : byPoint.keySet()) {
            if (!expectedPoints.contains(point)) {
                throw ApiException.unprocessable("FRAME_UNKNOWN_POINT",
                        "采样帧包含未知测点（不在当前名单版本中）: " + point);
            }
        }
        for (String expected : expectedPoints) {
            if (!byPoint.containsKey(expected)) {
                throw ApiException.unprocessable("FRAME_POINT_MISSING",
                        "采样帧缺少在册测点，整帧拒绝不写部分读数: " + expected);
            }
        }

        if (!late) {
            Integer maxSeq = jdbc.queryForObject(
                    "SELECT MAX(seq_no) FROM gas_frame WHERE fumigation_id = ? AND late = FALSE",
                    Integer.class, orderId);
            if (maxSeq != null && request.seqNo() <= maxSeq) {
                throw ApiException.unprocessable("FRAME_SEQ_NOT_INCREASING",
                        "采样帧序号必须在单号内严格递增，当前最大序号: " + maxSeq);
            }
            Optional<Instant> latestMeasuredAt = jdbc.query(
                            "SELECT MAX(measured_at) FROM gas_frame WHERE fumigation_id = ? AND late = FALSE",
                            (rs, rowNum) -> Optional.ofNullable(rs.getTimestamp(1))
                                    .map(ts -> ts.toLocalDateTime().toInstant(ZoneOffset.UTC)),
                            orderId)
                    .stream().findFirst().orElse(Optional.empty());
            if (latestMeasuredAt.isPresent() && !request.measuredAt().isAfter(latestMeasuredAt.get())) {
                throw ApiException.unprocessable("FRAME_TIME_NOT_INCREASING",
                        "采样帧时刻必须在同单号内严格递增");
            }
        }

        BigDecimal worst = ordered.stream().map(FramePointReading::concentration)
                .max(BigDecimal::compareTo).orElseThrow();
        boolean safe = worst.compareTo(order.safetyThreshold()) <= 0;

        try {
            long frameId = siloService.insert(
                    "INSERT INTO gas_frame (fumigation_id, request_id, seq_no, measured_at, list_version,"
                            + " worst_concentration, safe, late, fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.seqNo(), utc(request.measuredAt()),
                    order.pointListVersion(), worst, safe, late, fingerprint, utc(Instant.now()));
            // 按名单登记顺序写各测点读数；整帧任一点失败则随事务整体回滚，不留部分读数
            for (String point : expectedPoints) {
                jdbc.update("INSERT INTO gas_frame_reading (frame_id, fumigation_id, point_code,"
                        + " concentration, created_at) VALUES (?, ?, ?, ?, ?)",
                        frameId, orderId, point, byPoint.get(point), utc(Instant.now()));
            }
            if (!late) {
                jdbc.update("UPDATE fumigation_order SET version = version + 1 WHERE id = ?", orderId);
            }
            return toFrameView(findFrameById(frameId).orElseThrow(), false);
        } catch (DuplicateKeyException duplicate) {
            FrameRow row = findFrameByRequestId(request.requestId()).orElse(null);
            if (row != null) {
                if (!row.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "requestId 已存在但业务字段不一致: " + request.requestId());
                }
                return toFrameView(row, true);
            }
            throw ApiException.unprocessable("FRAME_SEQ_DUPLICATED",
                    "采样帧序号在同单号内必须唯一: " + request.seqNo());
        }
    }

    /** 查询熏蒸单全部采样帧（按序号排序，含解除后迟到帧）及其测点读数。 */
    public List<GasFrameView> frames(long orderId) {
        getOrder(orderId);
        return jdbc.query("SELECT * FROM gas_frame WHERE fumigation_id = ? ORDER BY seq_no, id",
                FumigationService::mapFrame, orderId).stream()
                .map(row -> toFrameView(row, false)).toList();
    }

    /**
     * 散气合成气体采样（旧单点入口）：单号内序号唯一、采样时刻严格递增、浓度为有限非负小数；
     * 正常采样推动熏蒸单版本 +1；解除后读数只记迟到（late=TRUE），不改版本与冻结证据。
     * 已启用多测点的受控单禁止使用本入口。
     */
    @Transactional
    public GasReadingView ingestGasReading(long orderId, GasReadingRequest request) {
        if (request.seqNo() < 1) {
            throw ApiException.badRequest("INVALID_SEQ_NO", "采样序号必须为正整数");
        }
        if (request.concentration().signum() < 0) {
            throw ApiException.badRequest("INVALID_CONCENTRATION", "合成气体浓度必须为有限非负小数");
        }
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), request.seqNo(),
                request.concentration().stripTrailingZeros().toPlainString(), request.measuredAt());

        Optional<ReadingRow> existing = findGasReadingByRequestId(request.requestId());
        if (existing.isPresent()) {
            ReadingRow row = existing.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toReadingView(row, true);
        }

        OrderRow order = lockOrder(orderId);
        Optional<ReadingRow> rechecked = findGasReadingByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            ReadingRow row = rechecked.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toReadingView(row, true);
        }

        if (order.multiPoint()) {
            // 受控多测点单（含解除后迟到证据）必须使用多测点采样帧入口，旧单点入口一律拒绝
            throw ApiException.unprocessable("MULTI_POINT_FRAME_REQUIRED",
                    "该受控单已启用多测点，必须提交覆盖全名单的采样帧，旧单点采样入口已拒绝");
        }
        boolean late = order.status() == FumigationStatus.RELEASED;
        if (!late && order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气中熏蒸单可采样，当前: " + order.status());
        }
        if (!late && order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }
        if (!late) {
            Optional<Instant> latestMeasuredAt = jdbc.query(
                            "SELECT MAX(measured_at) FROM gas_reading WHERE fumigation_id = ? AND late = FALSE",
                            (rs, rowNum) -> Optional.ofNullable(rs.getTimestamp(1))
                                    .map(ts -> ts.toLocalDateTime().toInstant(ZoneOffset.UTC)),
                            orderId)
                    .stream().findFirst().orElse(Optional.empty());
            if (latestMeasuredAt.isPresent() && !request.measuredAt().isAfter(latestMeasuredAt.get())) {
                throw ApiException.unprocessable("READING_TIME_NOT_INCREASING",
                        "采样时刻必须在同单号内严格递增");
            }
        }

        try {
            long readingId = siloService.insert(
                    "INSERT INTO gas_reading (fumigation_id, request_id, seq_no, concentration, measured_at,"
                            + " late, fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.seqNo(), request.concentration(),
                    utc(request.measuredAt()), late, fingerprint, utc(Instant.now()));
            if (!late) {
                jdbc.update("UPDATE fumigation_order SET version = version + 1 WHERE id = ?", orderId);
            }
            return toReadingView(findGasReadingById(readingId).orElseThrow(), false);
        } catch (DuplicateKeyException duplicate) {
            ReadingRow row = findGasReadingByRequestId(request.requestId()).orElse(null);
            if (row != null) {
                if (!row.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "requestId 已存在但业务字段不一致: " + request.requestId());
                }
                return toReadingView(row, true);
            }
            throw ApiException.unprocessable("SEQ_NO_DUPLICATED",
                    "采样序号在同单号内必须唯一: " + request.seqNo());
        }
    }

    /**
     * 双复核解除：两名不同复核人；持锁重查最新版本与读数，最后三点必须序号连续、
     * 浓度均 ≤ 安全阈值（恰等合法）、首末间隔 ≥ 30 分钟（恰等合法），越限或缺号不放行；
     * 任一失败均不释放仓锁与风机；成功则同事务原子结束散气、释放仓锁与风机。
     */
    @Transactional
    public ReleaseView release(long orderId, ReleaseFumigationRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), Fingerprints.stringList(request.reviewers()));

        Optional<ReleaseRow> existing = findReleaseByRequestId(request.requestId());
        if (existing.isPresent()) {
            ReleaseRow row = existing.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toReleaseView(row, true);
        }

        OrderRow order = lockOrder(orderId);
        Optional<ReleaseRow> rechecked = findReleaseByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            ReleaseRow row = rechecked.get();
            if (row.fumigationId() != orderId) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已用于其他熏蒸单: " + request.requestId());
            }
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务字段不一致: " + request.requestId());
            }
            return toReleaseView(row, true);
        }
        if (request.reviewers() == null || request.reviewers().size() != 2) {
            throw ApiException.badRequest("INVALID_REVIEWERS", "解除必须恰好提供两名复核人");
        }
        if (order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气中熏蒸单可解除，当前: " + order.status());
        }
        if (order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }
        if (order.frozenPlanVersion() == null) {
            throw ApiException.unprocessable("PLAN_NOT_FROZEN", "方案版本未冻结，禁止解除");
        }
        if (request.reviewers().get(0).equals(request.reviewers().get(1))) {
            throw ApiException.unprocessable("REVIEWERS_MUST_DIFFER", "两名复核人必须不同");
        }

        int firstSeqNo;
        int lastSeqNo;
        Integer releaseListVersion;
        if (order.multiPoint()) {
            // 受控多测点单：按最新名单版本复算连续安全帧段，旧版帧/越限帧/缺号都不得放行
            FrameWindow frameWindow = evaluateLatestFrames(order);
            firstSeqNo = frameWindow.first().seqNo();
            lastSeqNo = frameWindow.last().seqNo();
            releaseListVersion = order.pointListVersion();
        } else {
            // 旧单点单保持原规则：最新三点序号连续、全不越限、首末间隔 ≥30 分钟
            SafeWindow window = evaluateLatestReadings(order);
            firstSeqNo = window.first().seqNo();
            lastSeqNo = window.last().seqNo();
            releaseListVersion = null;
        }

        long releaseId;
        try {
            releaseId = siloService.insert(
                    "INSERT INTO fumigation_release (fumigation_id, request_id, expected_version, reviewer1,"
                            + " reviewer2, checked_version, last_seq_no, first_seq_no, list_version,"
                            + " fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.expectedVersion(),
                    request.reviewers().get(0), request.reviewers().get(1),
                    order.version(), lastSeqNo, firstSeqNo, releaseListVersion, fingerprint,
                    utc(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            // 并发解除：同 requestId 重放；同单异 requestId 判冲突（只有一个解除可成功）
            ReleaseRow row = findReleaseByRequestId(request.requestId()).orElse(null);
            if (row != null) {
                if (!row.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "requestId 已存在但业务字段不一致: " + request.requestId());
                }
                return toReleaseView(row, true);
            }
            throw ApiException.conflict("RELEASE_ALREADY_DONE",
                    "该熏蒸单已被并发解除，资源释放以首次结果为准");
        }

        // 原子结束散气：熏蒸单终态、仓锁释放回日常监测、风机释放
        jdbc.update("UPDATE fumigation_order SET status = 'RELEASED', released_at = ?,"
                + " version = version + 1 WHERE id = ?", utc(Instant.now()), orderId);
        jdbc.update("UPDATE fan SET status = 'IDLE', locked_fumigation_id = NULL"
                + " WHERE locked_fumigation_id = ?", orderId);
        jdbc.update("UPDATE silo SET status = 'MONITORING' WHERE id = ?", order.siloId());
        return toReleaseView(findReleaseById(releaseId).orElseThrow(), false);
    }

    /** 查询熏蒸单（含审批人列表），不存在 404。 */
    public FumigationView getOrder(long orderId) {
        OrderRow order = findOrderById(orderId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId));
        return toView(order, findApprovers(orderId), false);
    }

    /** 查询熏蒸单全部气体读数（按序号排序，含解除后迟到读数）。 */
    public List<GasReadingView> readings(long orderId) {
        getOrder(orderId);
        return jdbc.query("SELECT * FROM gas_reading WHERE fumigation_id = ? ORDER BY seq_no, id",
                FumigationService::mapGasReading, orderId).stream()
                .map(row -> toReadingView(row, false)).toList();
    }

    /** 查询解除双复核记录，不存在 404。 */
    public ReleaseView getRelease(long orderId) {
        return toReleaseView(findReleaseByOrderId(orderId).orElseThrow(
                () -> ApiException.notFound("RELEASE_NOT_FOUND", "解除记录不存在: " + orderId)), false);
    }

    /**
     * 评估最新三点：取非迟到读数按序号倒序前三点，要求三点齐备且序号连续（缺号不放行）、
     * 三点浓度均不超过安全阈值（越限打断，不放行）、首末时刻间隔 ≥ 30 分钟。
     */
    private SafeWindow evaluateLatestReadings(OrderRow order) {
        List<ReadingRow> latestDesc = jdbc.query(
                "SELECT * FROM gas_reading WHERE fumigation_id = ? AND late = FALSE"
                        + " ORDER BY seq_no DESC, id DESC LIMIT ?",
                FumigationService::mapGasReading, order.id(), SAFE_RUN_LENGTH);
        if (latestDesc.size() < SAFE_RUN_LENGTH) {
            throw ApiException.unprocessable("GAS_SEQUENCE_INCOMPLETE",
                    "安全采样点不足三点，缺号不视为安全，禁止解除");
        }
        List<ReadingRow> window = List.of(latestDesc.get(2), latestDesc.get(1), latestDesc.get(0));
        ReadingRow first = window.get(0);
        ReadingRow middle = window.get(1);
        ReadingRow last = window.get(2);
        if (last.seqNo() - first.seqNo() != SAFE_RUN_LENGTH - 1
                || middle.seqNo() != first.seqNo() + 1) {
            throw ApiException.unprocessable("GAS_SEQUENCE_INCOMPLETE",
                    "最新三点序号不连续，缺号不视为安全，禁止解除");
        }
        for (ReadingRow reading : window) {
            if (reading.concentration().compareTo(order.safetyThreshold()) > 0) {
                throw ApiException.unprocessable("GAS_NOT_SAFE",
                        "连续三点中存在超过安全阈值的读数，禁止解除");
            }
        }
        Duration gap = Duration.between(first.measuredAt(), last.measuredAt());
        if (gap.compareTo(MIN_FIRST_LAST_GAP) < 0) {
            throw ApiException.unprocessable("AERATION_DURATION_SHORT",
                    "安全三点首末间隔不足30分钟，禁止解除");
        }
        return new SafeWindow(first, last);
    }

    /**
     * 多测点解除评估：只取当前名单版本的非迟到帧（增点后旧版帧一律不参与），
     * 最新三帧必须齐备、序号连续（缺号/不足三帧不视为安全）、三帧全部安全（任一帧任一点越限打断）、
     * 首末帧采样时刻间隔 ≥30 分钟（恰等合法）。帧安全以入库时按当时阈值冻结的 safe 为准，
     * 这里同时复算 worst_concentration ≤ 阈值，避免阈值口径漂移。
     */
    private FrameWindow evaluateLatestFrames(OrderRow order) {
        int listVersion = order.pointListVersion() == null ? 0 : order.pointListVersion();
        List<FrameRow> latestDesc = jdbc.query(
                "SELECT * FROM gas_frame WHERE fumigation_id = ? AND late = FALSE AND list_version = ?"
                        + " ORDER BY seq_no DESC, id DESC LIMIT ?",
                FumigationService::mapFrame, order.id(), listVersion, SAFE_RUN_LENGTH);
        if (latestDesc.size() < SAFE_RUN_LENGTH) {
            throw ApiException.unprocessable("GAS_SEQUENCE_INCOMPLETE",
                    "当前名单版本下安全完整帧不足三帧（增点后须从新版首帧重新累计），禁止解除");
        }
        FrameRow first = latestDesc.get(2);
        FrameRow middle = latestDesc.get(1);
        FrameRow last = latestDesc.get(0);
        if (last.seqNo() - first.seqNo() != SAFE_RUN_LENGTH - 1
                || middle.seqNo() != first.seqNo() + 1) {
            throw ApiException.unprocessable("GAS_SEQUENCE_INCOMPLETE",
                    "当前名单版本下最新三帧序号不连续，缺号打断连续帧计数，禁止解除");
        }
        for (FrameRow frame : List.of(first, middle, last)) {
            if (!frame.safe() || frame.worstConcentration().compareTo(order.safetyThreshold()) > 0) {
                throw ApiException.unprocessable("GAS_NOT_SAFE",
                        "连续三帧中存在任一测点超过安全阈值的帧，禁止解除");
            }
        }
        Duration gap = Duration.between(first.measuredAt(), last.measuredAt());
        if (gap.compareTo(MIN_FIRST_LAST_GAP) < 0) {
            throw ApiException.unprocessable("AERATION_DURATION_SHORT",
                    "安全三帧首末间隔不足30分钟，禁止解除");
        }
        return new FrameWindow(first, last);
    }

    /**
     * 复算当前名单版本下的有效连续安全帧段：从最新一帧向前，遇越限帧、序号缺口或旧名单版本帧即中断。
     * releasable 表示该段最新三帧首末间隔已达 30 分钟。供查询核对，不改变任何状态。
     */
    public FrameChainView frameChain(long orderId) {
        OrderRow order = findOrderById(orderId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId));
        if (!order.multiPoint() || order.pointListVersion() == null) {
            throw ApiException.notFound("POINT_LIST_NOT_FOUND", "该熏蒸单未登记多测点名单: " + orderId);
        }
        int listVersion = order.pointListVersion();
        List<FrameRow> asc = jdbc.query(
                "SELECT * FROM gas_frame WHERE fumigation_id = ? AND late = FALSE AND list_version = ?"
                        + " ORDER BY seq_no ASC, id ASC",
                FumigationService::mapFrame, orderId, listVersion);

        List<FrameRow> run = new java.util.ArrayList<>();
        for (int i = asc.size() - 1; i >= 0; i--) {
            FrameRow frame = asc.get(i);
            if (!frame.safe() || frame.worstConcentration().compareTo(order.safetyThreshold()) > 0) {
                break;
            }
            if (!run.isEmpty() && run.get(0).seqNo() != frame.seqNo() + 1) {
                break;
            }
            run.add(0, frame);
        }

        boolean releasable = false;
        Integer firstSeqNo = null;
        Integer lastSeqNo = null;
        Instant firstMeasuredAt = null;
        Instant lastMeasuredAt = null;
        if (run.size() >= SAFE_RUN_LENGTH) {
            FrameRow firstOfThree = run.get(run.size() - SAFE_RUN_LENGTH);
            FrameRow lastOfThree = run.get(run.size() - 1);
            firstSeqNo = firstOfThree.seqNo();
            lastSeqNo = lastOfThree.seqNo();
            firstMeasuredAt = firstOfThree.measuredAt();
            lastMeasuredAt = lastOfThree.measuredAt();
            releasable = !Duration.between(firstMeasuredAt, lastMeasuredAt).minus(MIN_FIRST_LAST_GAP).isNegative();
        }
        List<GasFrameView> runViews = run.stream().map(frame -> toFrameView(frame, false)).toList();
        return new FrameChainView(listVersion, run.size(), releasable, firstSeqNo, lastSeqNo,
                firstMeasuredAt, lastMeasuredAt, runViews);
    }

    private void assertNoWindowOverlap(long siloId, Instant windowStart, Instant windowEnd) {
        // 普通通风窗以 Asia/Shanghai 本地墙钟时间存储；熏蒸窗为 UTC，需转换到同一时间轴再比较
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDateTime ventilationStart = LocalDateTime.ofInstant(windowStart, zone);
        LocalDateTime ventilationEnd = LocalDateTime.ofInstant(windowEnd, zone);
        Integer ventilationOverlaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND window_start < ? AND ? < window_end",
                Integer.class, siloId, ventilationEnd, ventilationStart);
        if (ventilationOverlaps != null && ventilationOverlaps > 0) {
            throw ApiException.unprocessable("WINDOW_CONFLICT", "熏蒸作业窗与既有普通通风窗重叠（端点相接不冲突）");
        }
        Integer fumigationOverlaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_order WHERE silo_id = ? AND status <> 'RELEASED'"
                        + " AND window_start < ? AND ? < window_end",
                Integer.class, siloId, utc(windowEnd), utc(windowStart));
        if (fumigationOverlaps != null && fumigationOverlaps > 0) {
            throw ApiException.unprocessable("WINDOW_CONFLICT", "熏蒸作业窗与既有未终结熏蒸窗重叠（端点相接不冲突）");
        }
    }

    private SiloStatus currentSiloStatus(long siloId) {
        return siloService.findSilo(siloId).orElseThrow(
                () -> ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + siloId)).status();
    }

    private void lockSilo(long siloId) {
        List<Long> ids = jdbc.query("SELECT id FROM silo WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), siloId);
        if (ids.isEmpty()) {
            throw ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + siloId);
        }
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM fumigation_order WHERE id = ? FOR UPDATE",
                FumigationService::mapOrder, orderId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId);
        }
        return rows.get(0);
    }

    private FumigationView replayOrConflict(OrderRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致: " + row.requestId());
        }
        return toView(row, findApprovers(row.id()), true);
    }

    /**
     * 状态推进动作（开始/转散气）幂等重放：同 requestId 且指纹一致返回首次冻结结果；
     * 同键异参 409；异键重复推进 409（动作已完成）。
     */
    private FumigationView replayAction(OrderRow order, String storedRequestId, String storedFingerprint,
                                        String fingerprint, String requestId) {
        if (!storedRequestId.equals(requestId)) {
            throw ApiException.conflict("ACTION_ALREADY_DONE",
                    "该状态推进动作已使用其他 requestId 完成: " + storedRequestId);
        }
        if (!storedFingerprint.equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致: " + requestId);
        }
        return toView(findOrderById(order.id()).orElseThrow(), findApprovers(order.id()), true);
    }

    private Optional<OrderRow> findOrderByColumn(String column, String value) {
        return jdbc.query("SELECT * FROM fumigation_order WHERE " + column + " = ?",
                FumigationService::mapOrder, value).stream().findFirst();
    }

    private Optional<OrderRow> findOrderById(long orderId) {
        return findOrderByColumn("id", String.valueOf(orderId));
    }

    private List<String> findApprovers(long orderId) {
        return jdbc.query("SELECT approver FROM fumigation_approval WHERE fumigation_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getString("approver"), orderId);
    }

    private Optional<ApprovalRow> findApprovalByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_approval WHERE request_id = ?",
                (rs, rowNum) -> new ApprovalRow(rs.getLong("id"), rs.getLong("fumigation_id"),
                        rs.getString("approver"), rs.getString("fingerprint")),
                requestId).stream().findFirst();
    }

    private Optional<ReadingRow> findGasReadingByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM gas_reading WHERE request_id = ?",
                FumigationService::mapGasReading, requestId).stream().findFirst();
    }

    private Optional<ReadingRow> findGasReadingById(long readingId) {
        return jdbc.query("SELECT * FROM gas_reading WHERE id = ?",
                FumigationService::mapGasReading, readingId).stream().findFirst();
    }

    private Optional<ReleaseRow> findReleaseByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_release WHERE request_id = ?",
                FumigationService::mapRelease, requestId).stream().findFirst();
    }

    private Optional<ReleaseRow> findReleaseByOrderId(long orderId) {
        return jdbc.query("SELECT * FROM fumigation_release WHERE fumigation_id = ?",
                FumigationService::mapRelease, orderId).stream().findFirst();
    }

    private Optional<ReleaseRow> findReleaseById(long releaseId) {
        return jdbc.query("SELECT * FROM fumigation_release WHERE id = ?",
                FumigationService::mapRelease, releaseId).stream().findFirst();
    }

    // ---------------- 多测点名单辅助 ----------------

    private List<String> listPoints(long orderId) {
        return jdbc.query("SELECT point_code FROM gas_point WHERE fumigation_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getString("point_code"), orderId);
    }

    /** 查询截至指定名单版本的在册测点（按登记顺序），用于幂等重放冻结结果。 */
    private List<String> listPointsAtVersion(long orderId, int listVersion) {
        return jdbc.query("SELECT point_code FROM gas_point WHERE fumigation_id = ? AND list_version <= ?"
                        + " ORDER BY id",
                (rs, rowNum) -> rs.getString("point_code"), orderId, listVersion);
    }

    /** 入参测点去空、去重校验：空/空白 400，重复编码 422；保留入参顺序。 */
    private static List<String> normalizeDistinctPoints(List<String> points, String opType) {
        if (points == null || points.isEmpty()) {
            throw ApiException.badRequest("INVALID_POINT_LIST", "测点名单不能为空");
        }
        List<String> normalized = new java.util.ArrayList<>();
        for (String point : points) {
            if (point == null || point.isBlank()) {
                throw ApiException.badRequest("INVALID_POINT_CODE", "测点编码不能为空");
            }
            if (normalized.contains(point)) {
                throw ApiException.unprocessable("POINT_DUPLICATED_IN_REQUEST",
                        "同一名单操作中测点不可重复: " + point);
            }
            normalized.add(point);
        }
        return List.copyOf(normalized);
    }

    private static String pointOpFingerprint(long orderId, String opType, String requestId,
                                             int expectedVersion, List<String> points) {
        return Fingerprints.structured(requestId, orderId, opType, expectedVersion,
                Fingerprints.stringList(points));
    }

    private Optional<PointOpRow> findPointOpByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM gas_point_op WHERE request_id = ?",
                (rs, rowNum) -> new PointOpRow(rs.getLong("id"), rs.getLong("fumigation_id"),
                        rs.getString("request_id"), rs.getString("op_type"),
                        rs.getInt("expected_version"), rs.getInt("list_version"),
                        rs.getString("fingerprint")),
                requestId).stream().findFirst();
    }

    /** 名单操作幂等重放：跨单/异参 409，同参返回首次冻结的名单版本与当时在册测点。 */
    private PointListView replayPointOp(long orderId, PointOpRow row, String fingerprint) {
        if (row.fumigationId() != orderId) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已用于其他熏蒸单: " + row.requestId());
        }
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致: " + row.requestId());
        }
        return new PointListView(row.listVersion(), listPointsAtVersion(orderId, row.listVersion()), true);
    }

    private static ApiException versionConflict(int expected, int actual) {
        return ApiException.conflict("VERSION_CONFLICT",
                "期望版本 " + expected + " 与当前版本 " + actual + " 不一致");
    }

    // ---------------- 多测点采样帧辅助 ----------------

    /** 帧内全部测点与浓度的结构化指纹载荷：点数 + 逐点（编码长度定界、浓度规范小数）。 */
    private static String frameReadingsPayload(List<FramePointReading> readings) {
        List<String> encoded = new java.util.ArrayList<>();
        for (FramePointReading reading : readings) {
            encoded.add(reading.pointCode() + "="
                    + reading.concentration().stripTrailingZeros().toPlainString());
        }
        return Fingerprints.stringList(encoded);
    }

    private Optional<FrameRow> findFrameByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM gas_frame WHERE request_id = ?",
                FumigationService::mapFrame, requestId).stream().findFirst();
    }

    private Optional<FrameRow> findFrameById(long frameId) {
        return jdbc.query("SELECT * FROM gas_frame WHERE id = ?",
                FumigationService::mapFrame, frameId).stream().findFirst();
    }

    private List<FrameReadingView> findFrameReadings(long frameId) {
        return jdbc.query("SELECT point_code, concentration FROM gas_frame_reading WHERE frame_id = ?"
                        + " ORDER BY id",
                (rs, rowNum) -> new FrameReadingView(rs.getString("point_code"),
                        rs.getBigDecimal("concentration")), frameId);
    }

    private static FrameRow mapFrame(ResultSet rs, int rowNum) throws SQLException {
        return new FrameRow(rs.getLong("id"), rs.getLong("fumigation_id"),
                rs.getString("request_id"), rs.getInt("seq_no"), toInstant(rs, "measured_at"),
                rs.getInt("list_version"), rs.getBigDecimal("worst_concentration"),
                rs.getBoolean("safe"), rs.getBoolean("late"), rs.getString("fingerprint"));
    }

    private GasFrameView toFrameView(FrameRow row, boolean replayed) {
        return new GasFrameView(row.id(), row.fumigationId(), row.seqNo(), row.measuredAt(),
                row.listVersion(), row.worstConcentration(), row.safe(), row.late(),
                findFrameReadings(row.id()), replayed);
    }

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getLong("silo_id"),
                rs.getString("request_id"),
                rs.getString("order_no"),
                toInstant(rs, "window_start"),
                toInstant(rs, "window_end"),
                rs.getString("synthetic_agent"),
                rs.getInt("plan_version"),
                (Integer) rs.getObject("frozen_plan_version"),
                rs.getBigDecimal("safety_threshold"),
                FumigationStatus.valueOf(rs.getString("status")),
                (Long) rs.getObject("fan_id"),
                toInstant(rs, "started_at"),
                toInstant(rs, "aeration_started_at"),
                toInstant(rs, "released_at"),
                rs.getString("fingerprint"),
                rs.getString("start_request_id"),
                rs.getString("start_fingerprint"),
                rs.getString("aeration_request_id"),
                rs.getString("aeration_fingerprint"),
                rs.getInt("version"),
                rs.getBoolean("multi_point"),
                (Integer) rs.getObject("point_list_version"));
    }

    private static ReadingRow mapGasReading(ResultSet rs, int rowNum) throws SQLException {
        return new ReadingRow(rs.getLong("id"), rs.getLong("fumigation_id"), rs.getInt("seq_no"),
                rs.getBigDecimal("concentration"), toInstant(rs, "measured_at"),
                rs.getBoolean("late"), rs.getString("fingerprint"), rs.getString("request_id"));
    }

    private static ReleaseRow mapRelease(ResultSet rs, int rowNum) throws SQLException {
        return new ReleaseRow(rs.getLong("id"), rs.getLong("fumigation_id"),
                rs.getString("request_id"), rs.getInt("expected_version"),
                rs.getString("reviewer1"), rs.getString("reviewer2"),
                rs.getInt("checked_version"), rs.getInt("last_seq_no"), rs.getInt("first_seq_no"),
                (Integer) rs.getObject("list_version"),
                rs.getString("fingerprint"), toInstant(rs, "created_at"));
    }

    /** Instant → 裸 UTC LocalDateTime，存入无时区 TIMESTAMP，不依赖 JVM 默认时区。 */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 无时区 TIMESTAMP（裸 UTC 墙钟）→ Instant。 */
    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private static FumigationView toView(OrderRow row, List<String> approvers, boolean replayed) {
        return new FumigationView(row.id(), row.siloId(), row.orderNo(), row.windowStart(),
                row.windowEnd(), row.syntheticAgent(), row.planVersion(), row.frozenPlanVersion(),
                row.safetyThreshold(), row.status(), row.fanId(), row.startedAt(),
                row.aerationStartedAt(), row.releasedAt(), row.version(), List.copyOf(approvers),
                row.multiPoint(), row.pointListVersion(), replayed);
    }

    private static GasReadingView toReadingView(ReadingRow row, boolean replayed) {
        return new GasReadingView(row.id(), row.fumigationId(), row.seqNo(), row.concentration(),
                row.measuredAt(), row.late(), replayed);
    }

    private static ReleaseView toReleaseView(ReleaseRow row, boolean replayed) {
        return new ReleaseView(row.id(), row.fumigationId(), row.checkedVersion(),
                row.lastSeqNo(), row.firstSeqNo(), row.listVersion(),
                List.of(row.reviewer1(), row.reviewer2()),
                row.createdAt(), replayed);
    }

    private record OrderRow(long id, long siloId, String requestId, String orderNo,
                            Instant windowStart, Instant windowEnd, String syntheticAgent,
                            int planVersion, Integer frozenPlanVersion, BigDecimal safetyThreshold,
                            FumigationStatus status, Long fanId, Instant startedAt,
                            Instant aerationStartedAt, Instant releasedAt, String fingerprint,
                            String startRequestId, String startFingerprint,
                            String aerationRequestId, String aerationFingerprint, int version,
                            boolean multiPoint, Integer pointListVersion) {
    }

    private record ApprovalRow(long id, long fumigationId, String approver, String fingerprint) {
    }

    private record ReadingRow(long id, long fumigationId, int seqNo, BigDecimal concentration,
                              Instant measuredAt, boolean late, String fingerprint, String requestId) {
    }

    private record ReleaseRow(long id, long fumigationId, String requestId, int expectedVersion,
                              String reviewer1, String reviewer2, int checkedVersion,
                              int lastSeqNo, int firstSeqNo, Integer listVersion,
                              String fingerprint, Instant createdAt) {
    }

    private record SafeWindow(ReadingRow first, ReadingRow last) {
    }

    private record FrameWindow(FrameRow first, FrameRow last) {
    }

    private record PointOpRow(long id, long fumigationId, String requestId, String opType,
                              int expectedVersion, int listVersion, String fingerprint) {
    }

    private record FrameRow(long id, long fumigationId, String requestId, int seqNo,
                            Instant measuredAt, int listVersion, BigDecimal worstConcentration,
                            boolean safe, boolean late, String fingerprint) {
    }
}
