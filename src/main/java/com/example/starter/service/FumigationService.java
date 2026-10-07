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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.starter.dto.GrainDtos.AddRosterPointsRequest;
import com.example.starter.dto.GrainDtos.ApplyFumigationRequest;
import com.example.starter.dto.GrainDtos.ApproveFumigationRequest;
import com.example.starter.dto.GrainDtos.BeginAerationRequest;
import com.example.starter.dto.GrainDtos.FramePointReading;
import com.example.starter.dto.GrainDtos.FramePointView;
import com.example.starter.dto.GrainDtos.FumigationView;
import com.example.starter.dto.GrainDtos.GasFrameRequest;
import com.example.starter.dto.GrainDtos.GasFrameView;
import com.example.starter.dto.GrainDtos.GasReadingRequest;
import com.example.starter.dto.GrainDtos.GasReadingView;
import com.example.starter.dto.GrainDtos.GasSafetyView;
import com.example.starter.dto.GrainDtos.RegisterRosterRequest;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.dto.GrainDtos.RosterView;
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
 * <p>多测点受控单：散气前登记 2 至 10 个不同测点并冻结名单版本 1；采样以帧为单位，
 * 每帧含递增序号、UTC 采样时刻与全名单有限非负浓度，缺点/重复点/未知点/非递增时刻全帧拒绝；
 * 完整帧全点 ≤ 安全阈值（恰等合法）才计安全，任一点越限或序号缺口打断连续计数；
 * 连续三帧安全且首末间隔 ≥ 30 分钟（恰等合法）方可双复核解除。散气期间可增点不可删点，
 * 增点生成新名单版本并使待解除确认失效，须从新版首个完整帧重新累计；旧读数与审批意见保留。
 * 未启用多测点的旧单保持单点采样原行为；受控单旧单点采样入口拒绝。
 *
 * <p>版本从 1 开始，每次成功变更加 1；写操作均带 requestId 幂等，
 * 指纹为 requestId 与全部业务参数（含版本、集合、精确时刻）的结构化 SHA-256，
 * 同键同参重放首次冻结结果，同键异参 409，业务失败回滚不占键。
 */
@Service
public class FumigationService {

    /** 解除所需连续安全点/帧数。 */
    static final int SAFE_RUN_LENGTH = 3;
    /** 连续安全三点/三帧首末最小间隔，恰等合法。 */
    static final Duration MIN_FIRST_LAST_GAP = Duration.ofMinutes(30);
    /** 测点名单最小点数。 */
    static final int MIN_ROSTER_POINTS = 2;
    /** 测点名单最大点数。 */
    static final int MAX_ROSTER_POINTS = 10;

    /** 采样帧基础查询：关联名单版本号。 */
    private static final String FRAME_SELECT =
            "SELECT f.*, r.roster_version FROM gas_frame f"
                    + " JOIN fumigation_roster r ON r.id = f.roster_id";

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

    /**
     * 散气合成气体采样：单号内序号唯一、采样时刻严格递增、浓度为有限非负小数；
     * 正常采样推动熏蒸单版本 +1；解除后读数只记迟到（late=TRUE），不改版本与冻结证据。
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

        if (currentRoster(orderId).isPresent()) {
            throw ApiException.unprocessable("MULTI_POINT_REQUIRED",
                    "受控熏蒸单须使用多测点采样帧入口，旧单点采样拒绝");
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
     * 双复核解除：两名不同复核人；持锁重查最新版本与读数/帧。未启用多测点的旧单要求最后三点
     * 序号连续、浓度均 ≤ 安全阈值、首末间隔 ≥ 30 分钟；受控单按当前名单版本的最新三帧评估，
     * 要求三帧序号连续、帧内全点 ≤ 阈值（恰等合法）、首末间隔 ≥ 30 分钟（恰等合法），
     * 越限帧或序号缺口打断计数，增点后须从新版首个完整帧重新累计；
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

        // 受控单按当前名单版本的最新三帧评估全点条件；旧单保持单点三点评估
        RosterRow roster = currentRoster(orderId).orElse(null);
        SafeWindow window;
        Integer checkedRosterVersion;
        if (roster != null) {
            window = evaluateLatestFrames(roster);
            checkedRosterVersion = roster.rosterVersion();
        } else {
            window = evaluateLatestReadings(order);
            checkedRosterVersion = null;
        }

        long releaseId;
        try {
            releaseId = siloService.insert(
                    "INSERT INTO fumigation_release (fumigation_id, request_id, expected_version, reviewer1,"
                            + " reviewer2, checked_version, last_seq_no, first_seq_no, checked_roster_version,"
                            + " fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.expectedVersion(),
                    request.reviewers().get(0), request.reviewers().get(1),
                    order.version(), window.lastSeqNo(), window.firstSeqNo(), checkedRosterVersion,
                    fingerprint, utc(Instant.now()));
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
        return new SafeWindow(first.seqNo(), last.seqNo());
    }

    /**
     * 评估当前名单版本的最新三帧：取该名单版本非迟到帧按序号倒序前三帧，要求三帧齐备且序号
     * 连续（序号缺口打断，不放行）、三帧均为全点 ≤ 阈值的安全帧（任一点越限打断，不放行）、
     * 首末时刻间隔 ≥ 30 分钟（恰等合法）；旧名单版本的帧不参与，增点后须重新累计。
     */
    private SafeWindow evaluateLatestFrames(RosterRow roster) {
        List<FrameRow> latestDesc = jdbc.query(
                FRAME_SELECT + " WHERE f.fumigation_id = ? AND f.roster_id = ? AND f.late = FALSE"
                        + " ORDER BY f.seq_no DESC, f.id DESC LIMIT ?",
                FumigationService::mapFrame, roster.fumigationId(), roster.id(), SAFE_RUN_LENGTH);
        if (latestDesc.size() < SAFE_RUN_LENGTH) {
            throw ApiException.unprocessable("GAS_SEQUENCE_INCOMPLETE",
                    "当前名单版本安全帧不足三帧，须从新版首个完整帧重新累计，禁止解除");
        }
        List<FrameRow> window = List.of(latestDesc.get(2), latestDesc.get(1), latestDesc.get(0));
        FrameRow first = window.get(0);
        FrameRow middle = window.get(1);
        FrameRow last = window.get(2);
        if (last.seqNo() - first.seqNo() != SAFE_RUN_LENGTH - 1
                || middle.seqNo() != first.seqNo() + 1) {
            throw ApiException.unprocessable("GAS_SEQUENCE_INCOMPLETE",
                    "最新三帧序号不连续，序号缺口打断连续计数，禁止解除");
        }
        for (FrameRow frame : window) {
            if (!frame.safe()) {
                throw ApiException.unprocessable("GAS_NOT_SAFE",
                        "连续三帧中存在越限帧（任一点超过安全阈值），禁止解除");
            }
        }
        Duration gap = Duration.between(first.measuredAt(), last.measuredAt());
        if (gap.compareTo(MIN_FIRST_LAST_GAP) < 0) {
            throw ApiException.unprocessable("AERATION_DURATION_SHORT",
                    "安全三帧首末间隔不足30分钟，禁止解除");
        }
        return new SafeWindow(first.seqNo(), last.seqNo());
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
                rs.getInt("version"));
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
                (Integer) rs.getObject("checked_roster_version"),
                rs.getString("fingerprint"), toInstant(rs, "created_at"));
    }

    private static RosterRow mapRoster(ResultSet rs, int rowNum) throws SQLException {
        return new RosterRow(rs.getLong("id"), rs.getLong("fumigation_id"),
                rs.getInt("roster_version"), rs.getString("fingerprint"), rs.getString("request_id"));
    }

    private static FrameRow mapFrame(ResultSet rs, int rowNum) throws SQLException {
        return new FrameRow(rs.getLong("id"), rs.getLong("fumigation_id"), rs.getLong("roster_id"),
                rs.getInt("roster_version"), rs.getInt("seq_no"), toInstant(rs, "measured_at"),
                rs.getBigDecimal("max_concentration"), rs.getBoolean("safe"), rs.getBoolean("late"),
                rs.getString("fingerprint"), rs.getString("request_id"));
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
                row.aerationStartedAt(), row.releasedAt(), row.version(), List.copyOf(approvers), replayed);
    }

    private static GasReadingView toReadingView(ReadingRow row, boolean replayed) {
        return new GasReadingView(row.id(), row.fumigationId(), row.seqNo(), row.concentration(),
                row.measuredAt(), row.late(), replayed);
    }

    private static ReleaseView toReleaseView(ReleaseRow row, boolean replayed) {
        return new ReleaseView(row.id(), row.fumigationId(), row.checkedVersion(),
                row.lastSeqNo(), row.firstSeqNo(), row.checkedRosterVersion(),
                List.of(row.reviewer1(), row.reviewer2()), row.createdAt(), replayed);
    }

    /**
     * 登记测点名单：散气前（待审批/已批准/熏蒸中）登记 2 至 10 个不同测点并冻结为名单版本 1；
     * 登记后该单成为受控单，旧单点采样入口拒绝。携带 expectedVersion，成功版本 +1。
     */
    @Transactional
    public RosterView registerRoster(long orderId, RegisterRosterRequest request) {
        List<String> points = normalizePoints(request.points());
        if (points.size() < MIN_ROSTER_POINTS || points.size() > MAX_ROSTER_POINTS) {
            throw ApiException.badRequest("INVALID_POINTS", "测点名单须登记2至10个不同测点");
        }
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), Fingerprints.stringList(points));

        Optional<RosterRow> existing = findRosterByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayRoster(existing.get(), orderId, fingerprint, request.requestId());
        }

        OrderRow order = lockOrder(orderId);
        Optional<RosterRow> rechecked = findRosterByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayRoster(rechecked.get(), orderId, fingerprint, request.requestId());
        }
        if (order.status() != FumigationStatus.PENDING_APPROVAL
                && order.status() != FumigationStatus.APPROVED
                && order.status() != FumigationStatus.FUMIGATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气前（待审批/已批准/熏蒸中）可登记测点名单，当前: " + order.status());
        }
        if (currentRoster(orderId).isPresent()) {
            throw ApiException.unprocessable("ROSTER_ALREADY_EXISTS",
                    "测点名单已登记，只能增点不能删点或重登记");
        }
        if (order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }

        long rosterId;
        try {
            rosterId = siloService.insert(
                    "INSERT INTO fumigation_roster (fumigation_id, roster_version, request_id,"
                            + " expected_version, fingerprint, created_at) VALUES (?, 1, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.expectedVersion(), fingerprint,
                    utc(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            RosterRow row = findRosterByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            return replayRoster(row, orderId, fingerprint, request.requestId());
        }
        insertRosterPoints(rosterId, points);
        jdbc.update("UPDATE fumigation_order SET version = version + 1 WHERE id = ?", orderId);
        return toRosterView(findRosterById(rosterId).orElseThrow(), false);
    }

    /**
     * 增测点：只能增不能删；新点不得与当前名单重复，总数不超过 10；成功生成新名单版本
     * （旧版本只读保留）并使待解除确认失效，解除须从新版首个完整帧重新累计。
     */
    @Transactional
    public RosterView addRosterPoints(long orderId, AddRosterPointsRequest request) {
        List<String> newPoints = normalizePoints(request.points());
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), Fingerprints.stringList(newPoints));

        Optional<RosterRow> existing = findRosterByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayRoster(existing.get(), orderId, fingerprint, request.requestId());
        }

        OrderRow order = lockOrder(orderId);
        Optional<RosterRow> rechecked = findRosterByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayRoster(rechecked.get(), orderId, fingerprint, request.requestId());
        }
        RosterRow current = currentRoster(orderId).orElseThrow(
                () -> ApiException.unprocessable("ROSTER_NOT_REGISTERED",
                        "尚未登记测点名单，须先登记初始名单"));
        if (order.status() == FumigationStatus.RELEASED) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE", "熏蒸单已解除，禁止增测点");
        }
        if (order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }

        List<String> currentPoints = findRosterPoints(current.id());
        List<String> union = new ArrayList<>(currentPoints);
        for (String point : newPoints) {
            if (currentPoints.contains(point)) {
                throw ApiException.unprocessable("POINT_ALREADY_EXISTS",
                        "测点已存在于当前名单: " + point);
            }
            union.add(point);
        }
        if (union.size() > MAX_ROSTER_POINTS) {
            throw ApiException.unprocessable("ROSTER_TOO_LARGE",
                    "测点总数超过上限 " + MAX_ROSTER_POINTS + " 个");
        }
        List<String> sortedUnion = union.stream().sorted().toList();

        long rosterId;
        try {
            rosterId = siloService.insert(
                    "INSERT INTO fumigation_roster (fumigation_id, roster_version, request_id,"
                            + " expected_version, fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    orderId, current.rosterVersion() + 1, request.requestId(),
                    request.expectedVersion(), fingerprint, utc(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            RosterRow row = findRosterByRequestId(request.requestId()).orElseThrow(() -> duplicate);
            return replayRoster(row, orderId, fingerprint, request.requestId());
        }
        insertRosterPoints(rosterId, sortedUnion);
        jdbc.update("UPDATE fumigation_order SET version = version + 1 WHERE id = ?", orderId);
        return toRosterView(findRosterById(rosterId).orElseThrow(), false);
    }

    /**
     * 多测点采样帧：仅受控单可用；帧须覆盖当前名单全部测点且无重复点、未知点，
     * 同帧各点共用同一 UTC 时刻且单号内严格递增，浓度为有限非负小数；任一校验失败全帧拒绝，
     * 不写部分读数。帧安全 = 全点 ≤ 安全阈值（恰等合法）。解除后帧只记迟到（late=TRUE），
     * 不改版本与冻结证据。
     */
    @Transactional
    public GasFrameView ingestGasFrame(long orderId, GasFrameRequest request) {
        if (request.seqNo() < 1) {
            throw ApiException.badRequest("INVALID_SEQ_NO", "采样帧序号必须为正整数");
        }
        List<FramePointReading> readings = request.readings();
        List<String> codes = readings.stream().map(FramePointReading::pointCode).sorted().toList();
        for (int i = 1; i < codes.size(); i++) {
            if (codes.get(i).equals(codes.get(i - 1))) {
                throw ApiException.badRequest("INVALID_FRAME_POINTS",
                        "帧内存在重复测点，全帧拒绝: " + codes.get(i));
            }
        }
        for (FramePointReading reading : readings) {
            if (reading.concentration() == null || reading.concentration().signum() < 0) {
                throw ApiException.badRequest("INVALID_CONCENTRATION", "合成气体浓度必须为有限非负小数");
            }
        }
        List<String> encoded = readings.stream()
                .sorted(Comparator.comparing(FramePointReading::pointCode))
                .map(reading -> reading.pointCode() + "="
                        + reading.concentration().stripTrailingZeros().toPlainString())
                .toList();
        String fingerprint = Fingerprints.structured(request.requestId(), orderId,
                request.expectedVersion(), request.seqNo(), request.measuredAt(),
                Fingerprints.stringList(encoded));

        Optional<FrameRow> existing = findFrameByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayFrame(existing.get(), orderId, fingerprint, request.requestId());
        }

        OrderRow order = lockOrder(orderId);
        Optional<FrameRow> rechecked = findFrameByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayFrame(rechecked.get(), orderId, fingerprint, request.requestId());
        }

        RosterRow roster = currentRoster(orderId).orElseThrow(
                () -> ApiException.unprocessable("ROSTER_NOT_REGISTERED",
                        "未启用多测点的熏蒸单请使用旧单点采样入口"));
        boolean late = order.status() == FumigationStatus.RELEASED;
        if (!late && order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气中熏蒸单可采样，当前: " + order.status());
        }
        if (!late && order.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + order.version() + " 不一致");
        }

        // 全名单校验：缺点或未知点全帧拒绝，不写部分读数
        Set<String> expected = new HashSet<>(findRosterPoints(roster.id()));
        Set<String> actual = new HashSet<>(codes);
        if (!expected.equals(actual)) {
            throw ApiException.unprocessable("FRAME_POINTS_MISMATCH",
                    "采样帧须覆盖当前名单全部测点且无未知点，缺点/未知点全帧拒绝");
        }
        if (!late) {
            Optional<Instant> latestMeasuredAt = jdbc.query(
                            "SELECT MAX(measured_at) FROM gas_frame WHERE fumigation_id = ? AND late = FALSE",
                            (rs, rowNum) -> Optional.ofNullable(rs.getTimestamp(1))
                                    .map(ts -> ts.toLocalDateTime().toInstant(ZoneOffset.UTC)),
                            orderId)
                    .stream().findFirst().orElse(Optional.empty());
            if (latestMeasuredAt.isPresent() && !request.measuredAt().isAfter(latestMeasuredAt.get())) {
                throw ApiException.unprocessable("FRAME_TIME_NOT_INCREASING",
                        "采样帧时刻必须在同单号内严格递增，非递增时刻全帧拒绝");
            }
        }

        BigDecimal maxConcentration = readings.stream().map(FramePointReading::concentration)
                .max(Comparator.naturalOrder()).orElseThrow();
        boolean safe = maxConcentration.compareTo(order.safetyThreshold()) <= 0;
        try {
            long frameId = siloService.insert(
                    "INSERT INTO gas_frame (fumigation_id, roster_id, request_id, seq_no, measured_at,"
                            + " max_concentration, safe, late, fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    orderId, roster.id(), request.requestId(), request.seqNo(),
                    utc(request.measuredAt()), maxConcentration, safe, late, fingerprint,
                    utc(Instant.now()));
            for (FramePointReading reading : readings) {
                jdbc.update("INSERT INTO gas_frame_point (frame_id, point_code, concentration)"
                                + " VALUES (?, ?, ?)",
                        frameId, reading.pointCode(), reading.concentration());
            }
            if (!late) {
                jdbc.update("UPDATE fumigation_order SET version = version + 1 WHERE id = ?", orderId);
            }
            return toFrameView(findFrameById(frameId).orElseThrow(), false);
        } catch (DuplicateKeyException duplicate) {
            Optional<FrameRow> row = findFrameByRequestId(request.requestId());
            if (row.isPresent()) {
                return replayFrame(row.get(), orderId, fingerprint, request.requestId());
            }
            throw ApiException.unprocessable("SEQ_NO_DUPLICATED",
                    "采样帧序号在同单号内必须唯一: " + request.seqNo());
        }
    }

    /** 查询当前测点名单（含全部测点，按编码排序），未登记 404。 */
    public RosterView getRoster(long orderId) {
        getOrder(orderId);
        return currentRoster(orderId).map(row -> toRosterView(row, false)).orElseThrow(
                () -> ApiException.notFound("ROSTER_NOT_FOUND", "测点名单不存在: " + orderId));
    }

    /** 查询熏蒸单全部采样帧（按序号排序，含各点读数与每帧最差值，含解除后迟到帧）。 */
    public List<GasFrameView> gasFrames(long orderId) {
        getOrder(orderId);
        return jdbc.query(FRAME_SELECT + " WHERE f.fumigation_id = ? ORDER BY f.seq_no, f.id",
                FumigationService::mapFrame, orderId).stream()
                .map(row -> toFrameView(row, false)).toList();
    }

    /**
     * 复算当前名单版本的有效连续安全段：取该版本非迟到帧按序号倒序，从最新帧向回累计
     * 连续且安全的帧段；releasable 表示最近三帧连续安全且首末间隔 ≥ 30 分钟（恰等合法）。
     */
    public GasSafetyView gasSafety(long orderId) {
        getOrder(orderId);
        RosterRow roster = currentRoster(orderId).orElseThrow(
                () -> ApiException.notFound("ROSTER_NOT_FOUND", "测点名单不存在: " + orderId));
        List<FrameRow> desc = jdbc.query(
                FRAME_SELECT + " WHERE f.fumigation_id = ? AND f.roster_id = ? AND f.late = FALSE"
                        + " ORDER BY f.seq_no DESC, f.id DESC",
                FumigationService::mapFrame, orderId, roster.id());
        List<FrameRow> run = new ArrayList<>();
        Integer previousSeq = null;
        for (FrameRow frame : desc) {
            if (!frame.safe()) {
                break;
            }
            if (previousSeq != null && frame.seqNo() != previousSeq - 1) {
                break;
            }
            run.add(frame);
            previousSeq = frame.seqNo();
        }
        if (run.isEmpty()) {
            return new GasSafetyView(orderId, roster.rosterVersion(), 0,
                    null, null, null, null, null, false);
        }
        FrameRow last = run.get(0);
        FrameRow first = run.get(run.size() - 1);
        long gapSeconds = Duration.between(first.measuredAt(), last.measuredAt()).getSeconds();
        boolean releasable = false;
        if (run.size() >= SAFE_RUN_LENGTH) {
            FrameRow third = run.get(SAFE_RUN_LENGTH - 1);
            long windowGap = Duration.between(third.measuredAt(), last.measuredAt()).getSeconds();
            releasable = windowGap >= MIN_FIRST_LAST_GAP.getSeconds();
        }
        return new GasSafetyView(orderId, roster.rosterVersion(), run.size(),
                first.seqNo(), last.seqNo(), first.measuredAt(), last.measuredAt(),
                gapSeconds, releasable);
    }

    /** 名单请求测点规范化：非空校验、按编码排序、重复点拒绝。 */
    private static List<String> normalizePoints(List<String> points) {
        if (points == null || points.isEmpty()) {
            throw ApiException.badRequest("INVALID_POINTS", "测点名单不能为空");
        }
        List<String> sorted = points.stream().sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw ApiException.badRequest("INVALID_POINTS",
                        "测点名单存在重复测点: " + sorted.get(i));
            }
        }
        return sorted;
    }

    /** 名单幂等重放：同 requestId 且指纹一致返回首次冻结结果；同键异参或异单 409。 */
    private RosterView replayRoster(RosterRow row, long orderId, String fingerprint, String requestId) {
        if (row.fumigationId() != orderId) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已用于其他熏蒸单: " + requestId);
        }
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致: " + requestId);
        }
        return toRosterView(row, true);
    }

    /** 采样帧幂等重放：同 requestId 且指纹一致返回首次冻结结果；同键异参或异单 409。 */
    private GasFrameView replayFrame(FrameRow row, long orderId, String fingerprint, String requestId) {
        if (row.fumigationId() != orderId) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已用于其他熏蒸单: " + requestId);
        }
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在但业务字段不一致: " + requestId);
        }
        return toFrameView(row, true);
    }

    private Optional<RosterRow> currentRoster(long orderId) {
        return jdbc.query("SELECT * FROM fumigation_roster WHERE fumigation_id = ?"
                        + " ORDER BY roster_version DESC LIMIT 1",
                FumigationService::mapRoster, orderId).stream().findFirst();
    }

    private Optional<RosterRow> findRosterByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_roster WHERE request_id = ?",
                FumigationService::mapRoster, requestId).stream().findFirst();
    }

    private Optional<RosterRow> findRosterById(long rosterId) {
        return jdbc.query("SELECT * FROM fumigation_roster WHERE id = ?",
                FumigationService::mapRoster, rosterId).stream().findFirst();
    }

    private List<String> findRosterPoints(long rosterId) {
        return jdbc.query("SELECT point_code FROM fumigation_roster_point WHERE roster_id = ?"
                        + " ORDER BY point_code",
                (rs, rowNum) -> rs.getString("point_code"), rosterId);
    }

    private void insertRosterPoints(long rosterId, List<String> points) {
        for (String point : points) {
            jdbc.update("INSERT INTO fumigation_roster_point (roster_id, point_code) VALUES (?, ?)",
                    rosterId, point);
        }
    }

    private Optional<FrameRow> findFrameByRequestId(String requestId) {
        return jdbc.query(FRAME_SELECT + " WHERE f.request_id = ?",
                FumigationService::mapFrame, requestId).stream().findFirst();
    }

    private Optional<FrameRow> findFrameById(long frameId) {
        return jdbc.query(FRAME_SELECT + " WHERE f.id = ?",
                FumigationService::mapFrame, frameId).stream().findFirst();
    }

    private List<FramePointView> findFramePoints(long frameId) {
        return jdbc.query("SELECT point_code, concentration FROM gas_frame_point WHERE frame_id = ?"
                        + " ORDER BY point_code",
                (rs, rowNum) -> new FramePointView(rs.getString("point_code"),
                        rs.getBigDecimal("concentration")), frameId);
    }

    private RosterView toRosterView(RosterRow row, boolean replayed) {
        return new RosterView(row.id(), row.fumigationId(), row.rosterVersion(),
                findRosterPoints(row.id()), replayed);
    }

    private GasFrameView toFrameView(FrameRow row, boolean replayed) {
        return new GasFrameView(row.id(), row.fumigationId(), row.rosterVersion(), row.seqNo(),
                row.measuredAt(), row.maxConcentration(), row.safe(), row.late(),
                findFramePoints(row.id()), replayed);
    }

    private record OrderRow(long id, long siloId, String requestId, String orderNo,
                            Instant windowStart, Instant windowEnd, String syntheticAgent,
                            int planVersion, Integer frozenPlanVersion, BigDecimal safetyThreshold,
                            FumigationStatus status, Long fanId, Instant startedAt,
                            Instant aerationStartedAt, Instant releasedAt, String fingerprint,
                            String startRequestId, String startFingerprint,
                            String aerationRequestId, String aerationFingerprint, int version) {
    }

    private record ApprovalRow(long id, long fumigationId, String approver, String fingerprint) {
    }

    private record ReadingRow(long id, long fumigationId, int seqNo, BigDecimal concentration,
                              Instant measuredAt, boolean late, String fingerprint, String requestId) {
    }

    private record ReleaseRow(long id, long fumigationId, String requestId, int expectedVersion,
                              String reviewer1, String reviewer2, int checkedVersion,
                              int lastSeqNo, int firstSeqNo, Integer checkedRosterVersion,
                              String fingerprint, Instant createdAt) {
    }

    private record SafeWindow(int firstSeqNo, int lastSeqNo) {
    }

    private record RosterRow(long id, long fumigationId, int rosterVersion,
                             String fingerprint, String requestId) {
    }

    private record FrameRow(long id, long fumigationId, long rosterId, int rosterVersion,
                            int seqNo, Instant measuredAt, BigDecimal maxConcentration,
                            boolean safe, boolean late, String fingerprint, String requestId) {
    }
}
