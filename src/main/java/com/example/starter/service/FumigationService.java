package com.example.starter.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.starter.dto.FumigationDtos.ApplyFumigationRequest;
import com.example.starter.dto.FumigationDtos.ApprovalView;
import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.ConfirmationView;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationDetailView;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingView;
import com.example.starter.dto.FumigationDtos.ReleaseAssessment;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.FumigationDtos.StartRequest;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;
import com.example.starter.util.Fingerprints;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 熏蒸闭环服务：申请、双人批准、开始（原子锁仓并冻结方案版本）、结束转散气（绑定空闲风机）、
 * 散气采样（连续三点达标且首末间隔≥30分钟才可解除）、双人复核解除（原子释放仓锁与风机）。
 * 所有写操作 requestId 幂等（结构化指纹），expectedVersion 乐观锁，业务、版本与幂等结果同事务。
 */
@Service
public class FumigationService {

    /** 批准所需的不同审批人数 */
    static final int REQUIRED_APPROVALS = 2;
    /** 解除所需的不同复核人数 */
    static final int REQUIRED_CONFIRMATIONS = 2;
    /** 解除要求连续三点首末间隔至少 30 分钟（恰等合法） */
    static final long MIN_RELEASE_SPAN_MINUTES = 30;

    private final JdbcTemplate jdbc;
    private final SiloService siloService;

    public FumigationService(JdbcTemplate jdbc, SiloService siloService) {
        this.jdbc = jdbc;
        this.siloService = siloService;
    }

    /**
     * 申请熏蒸单。作业窗为 UTC 半开区间 [start, end)，与同仓普通通风窗口及既有熏蒸窗口互斥
     * （端点相接可共存）；版本从 1 开始；requestId 幂等，同键异参 409。
     */
    @Transactional
    public FumigationView apply(long siloId, ApplyFumigationRequest request) {
        if (!request.windowStart().isBefore(request.windowEnd())) {
            throw ApiException.badRequest("INVALID_WINDOW", "作业窗起点必须早于终点");
        }
        if (request.safeThreshold().signum() < 0) {
            throw ApiException.badRequest("INVALID_THRESHOLD", "合成安全阈值须为有限非负小数");
        }
        String fingerprint = Fingerprints.structured(request.requestId(), siloId, request.windowStart(),
                request.windowEnd(), request.plan(), request.safeThreshold());

        Optional<OrderRow> existing = findOrderByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayOrderOrConflict(existing.get(), fingerprint);
        }

        // 锁定粮仓行，串行化同一仓的并发申请
        SiloRow silo = lockSilo(siloId);

        // 持锁后复检幂等键，覆盖并发下同键请求在锁前检查落空的情形
        Optional<OrderRow> rechecked = findOrderByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayOrderOrConflict(rechecked.get(), fingerprint);
        }
        SiloStatus status = SiloStatus.valueOf(silo.status());
        if (status == SiloStatus.ARCHIVED) {
            throw ApiException.unprocessable("SILO_ARCHIVED", "粮仓已归档，禁止申请熏蒸");
        }
        if (status == SiloStatus.FUMIGATING || status == SiloStatus.AERATING) {
            throw ApiException.unprocessable("SILO_LOCKED", "粮仓处于熏蒸锁仓/散气中，禁止申请新的熏蒸");
        }

        // 与同仓普通通风窗口互斥（半开区间，端点相接不冲突）
        Integer ventilationConflicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND window_start < ? AND ? < window_end",
                Integer.class, siloId, request.windowEnd(), request.windowStart());
        if (ventilationConflicts != null && ventilationConflicts > 0) {
            throw ApiException.unprocessable("WINDOW_CONFLICT", "作业窗与普通通风窗口重叠（半开区间端点相接不冲突）");
        }
        // 与同仓既有熏蒸窗口互斥
        Integer fumigationConflicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_order WHERE silo_id = ? AND window_start < ? AND ? < window_end",
                Integer.class, siloId, request.windowEnd(), request.windowStart());
        if (fumigationConflicts != null && fumigationConflicts > 0) {
            throw ApiException.unprocessable("WINDOW_CONFLICT", "作业窗与既有熏蒸窗口重叠（半开区间端点相接不冲突）");
        }

        long orderId;
        try {
            orderId = siloService.insert(
                    "INSERT INTO fumigation_order (silo_id, request_id, window_start, window_end, plan,"
                            + " safe_threshold, fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    siloId, request.requestId(), request.windowStart(), request.windowEnd(),
                    request.plan(), request.safeThreshold(), fingerprint);
        } catch (DuplicateKeyException duplicate) {
            // 并发下同键请求落入唯一约束：按幂等重放或键冲突处理
            return replayOrderOrConflict(
                    findOrderByRequestId(request.requestId()).orElseThrow(() -> duplicate), fingerprint);
        }
        return toView(findOrderById(orderId).orElseThrow(), false);
    }

    /**
     * 审批：两名不同审批人共同批准后单据转入已批准；每次成功审批版本加 1。
     */
    @Transactional
    public ApprovalView approve(long orderId, ApproveRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId, request.approver(),
                request.expectedVersion());

        Optional<ApprovalRow> existing = findApprovalByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayApprovalOrConflict(existing.get(), fingerprint);
        }

        OrderRow order = lockOrder(orderId);

        Optional<ApprovalRow> rechecked = findApprovalByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayApprovalOrConflict(rechecked.get(), fingerprint);
        }
        if (order.status() != FumigationStatus.PENDING_APPROVAL) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅待审批熏蒸单可审批，当前: " + order.status());
        }
        if (request.approver().equals(order.approver1()) || request.approver().equals(order.approver2())) {
            throw ApiException.unprocessable("SAME_APPROVER", "审批人不得重复: " + request.approver());
        }
        requireVersion(order, request.expectedVersion());

        int approvalsBefore = countRows("fumigation_approval", orderId);
        int newVersion = order.version() + 1;
        boolean approved = approvalsBefore + 1 >= REQUIRED_APPROVALS;
        FumigationStatus statusAfter = approved ? FumigationStatus.APPROVED : FumigationStatus.PENDING_APPROVAL;
        try {
            siloService.insert(
                    "INSERT INTO fumigation_approval (order_id, request_id, approver, version_after,"
                            + " status_after, fingerprint) VALUES (?, ?, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.approver(), newVersion, statusAfter.name(),
                    fingerprint);
        } catch (DuplicateKeyException duplicate) {
            Optional<ApprovalRow> byRequest = findApprovalByRequestId(request.requestId());
            if (byRequest.isPresent()) {
                return replayApprovalOrConflict(byRequest.get(), fingerprint);
            }
            throw ApiException.unprocessable("SAME_APPROVER", "审批人不得重复: " + request.approver());
        }

        if (approved) {
            jdbc.update("UPDATE fumigation_order SET status = 'APPROVED', approver2 = ?, version = ?,"
                    + " approved_at = CURRENT_TIMESTAMP WHERE id = ?", request.approver(), newVersion, orderId);
        } else {
            jdbc.update("UPDATE fumigation_order SET approver1 = ?, version = ? WHERE id = ?",
                    request.approver(), newVersion, orderId);
        }
        return new ApprovalView(orderId, request.approver(), approvalsBefore + 1, newVersion, statusAfter, false);
    }

    /**
     * 开始熏蒸：实际开始时再查无执行中通风；原子锁仓并冻结方案版本，
     * 锁仓期间禁止人员进入与普通通风。
     */
    @Transactional
    public FumigationView start(long orderId, StartRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId, request.expectedVersion(),
                request.startedAt());

        OrderRow order = lockOrder(orderId);
        if (request.requestId().equals(order.startRequestId())) {
            if (!order.startFingerprint().equals(fingerprint)) {
                throw idempotencyConflict(request.requestId());
            }
            return toView(order, true);
        }
        if (order.status() != FumigationStatus.APPROVED) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅已批准熏蒸单可开始，当前: " + order.status());
        }
        requireVersion(order, request.expectedVersion());

        // 持仓锁后再查：不得有执行中通风
        SiloRow silo = lockSilo(order.siloId());
        SiloStatus siloStatus = SiloStatus.valueOf(silo.status());
        Integer executing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ventilation_job WHERE silo_id = ? AND status = 'EXECUTING'",
                Integer.class, order.siloId());
        if ((executing != null && executing > 0) || siloStatus == SiloStatus.VENTILATING) {
            throw ApiException.unprocessable("VENTILATION_EXECUTING", "存在执行中通风，禁止开始熏蒸");
        }
        if (siloStatus == SiloStatus.ARCHIVED) {
            throw ApiException.unprocessable("SILO_ARCHIVED", "粮仓已归档，禁止开始熏蒸");
        }
        if (siloStatus == SiloStatus.FUMIGATING || siloStatus == SiloStatus.AERATING) {
            throw ApiException.unprocessable("SILO_LOCKED", "粮仓已处于熏蒸锁仓/散气中");
        }

        int newVersion = order.version() + 1;
        jdbc.update("UPDATE fumigation_order SET status = 'FUMIGATING', version = ?,"
                        + " frozen_plan_version = ?, started_at = ?, start_request_id = ?, start_fingerprint = ?"
                        + " WHERE id = ?",
                newVersion, order.version(), request.startedAt(), request.requestId(), fingerprint, orderId);
        jdbc.update("UPDATE silo SET status = 'FUMIGATING' WHERE id = ?", order.siloId());
        return toView(findOrderById(orderId).orElseThrow(), false);
    }

    /**
     * 结束熏蒸转散气：原子绑定一台空闲风机（沿用风机唯一资源规则），
     * 风机锁定失败则整体回滚。
     */
    @Transactional
    public FumigationView finish(long orderId, FinishRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId, request.expectedVersion(),
                request.fanId(), request.finishedAt());

        OrderRow order = lockOrder(orderId);
        if (request.requestId().equals(order.finishRequestId())) {
            if (!order.finishFingerprint().equals(fingerprint)) {
                throw idempotencyConflict(request.requestId());
            }
            return toView(order, true);
        }
        if (order.status() != FumigationStatus.FUMIGATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅熏蒸中熏蒸单可结束，当前: " + order.status());
        }
        requireVersion(order, request.expectedVersion());

        // 风机存在性校验
        siloService.getFan(request.fanId());

        int newVersion = order.version() + 1;
        jdbc.update("UPDATE fumigation_order SET status = 'AERATING', version = ?, fan_id = ?,"
                        + " finished_at = ?, finish_request_id = ?, finish_fingerprint = ? WHERE id = ?",
                newVersion, request.fanId(), request.finishedAt(), request.requestId(), fingerprint, orderId);
        // 风机锁定与状态推进同事务：锁定失败则整体回滚
        int locked = jdbc.update(
                "UPDATE fan SET status = 'LOCKED', locked_fumigation_id = ? WHERE id = ? AND status = 'IDLE'",
                orderId, request.fanId());
        if (locked == 0) {
            throw ApiException.unprocessable("FAN_BUSY", "风机已被占用: " + request.fanId());
        }
        jdbc.update("UPDATE silo SET status = 'AERATING' WHERE id = ?", order.siloId());
        return toView(findOrderById(orderId).orElseThrow(), false);
    }

    /**
     * 散气采样：读数以(单号, 序号)唯一，时刻严格递增，浓度为有限非负小数；
     * 解除后迟到读数只记迟到事件，不改已冻结证据；requestId 幂等。
     */
    @Transactional
    public GasReadingView sample(long orderId, GasReadingRequest request) {
        if (request.concentration().signum() < 0) {
            throw ApiException.badRequest("INVALID_CONCENTRATION", "浓度须为有限非负小数");
        }
        String fingerprint = Fingerprints.structured(request.requestId(), orderId, request.seqNo(),
                request.concentration(), request.measuredAt());

        Optional<GasRow> existing = findGasByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayGasOrConflict(existing.get(), fingerprint);
        }
        if (findLateEvent(request.requestId())) {
            return new GasReadingView(null, orderId, request.seqNo(), request.concentration(),
                    request.measuredAt(), "LATE_RECORDED", true);
        }

        OrderRow order = lockOrder(orderId);

        Optional<GasRow> rechecked = findGasByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayGasOrConflict(rechecked.get(), fingerprint);
        }
        if (order.status() == FumigationStatus.RELEASED) {
            // 关闭后读数只记迟到，不改已冻结证据
            try {
                jdbc.update("INSERT INTO late_event (silo_id, request_id, event_type, payload)"
                                + " VALUES (?, ?, ?, ?)",
                        order.siloId(), request.requestId(), "LATE_GAS_READING",
                        "orderId=" + orderId + ", seqNo=" + request.seqNo()
                                + ", concentration=" + request.concentration()
                                + ", measuredAt=" + request.measuredAt());
            } catch (DuplicateKeyException duplicate) {
                return new GasReadingView(null, orderId, request.seqNo(), request.concentration(),
                        request.measuredAt(), "LATE_RECORDED", true);
            }
            return new GasReadingView(null, orderId, request.seqNo(), request.concentration(),
                    request.measuredAt(), "LATE_RECORDED", false);
        }
        if (order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气中熏蒸单可采样，当前: " + order.status());
        }

        Integer sameSeq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gas_reading WHERE order_id = ? AND seq_no = ?",
                Integer.class, orderId, request.seqNo());
        if (sameSeq != null && sameSeq > 0) {
            throw ApiException.unprocessable("SEQ_CONFLICT", "读数序号已存在: " + request.seqNo());
        }
        LocalDateTime maxMeasuredAt = jdbc.query(
                "SELECT MAX(measured_at) AS max_at FROM gas_reading WHERE order_id = ?",
                rs -> rs.next() && rs.getTimestamp("max_at") != null
                        ? rs.getTimestamp("max_at").toLocalDateTime() : null,
                orderId);
        if (maxMeasuredAt != null && !request.measuredAt().isAfter(maxMeasuredAt)) {
            throw ApiException.unprocessable("READING_TIME_NOT_INCREASING",
                    "采样时刻须严格晚于已有读数: " + maxMeasuredAt);
        }

        long readingId;
        try {
            readingId = siloService.insert(
                    "INSERT INTO gas_reading (order_id, request_id, seq_no, concentration, measured_at,"
                            + " fingerprint) VALUES (?, ?, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.seqNo(), request.concentration(),
                    request.measuredAt(), fingerprint);
        } catch (DuplicateKeyException duplicate) {
            Optional<GasRow> byRequest = findGasByRequestId(request.requestId());
            if (byRequest.isPresent()) {
                return replayGasOrConflict(byRequest.get(), fingerprint);
            }
            throw ApiException.unprocessable("SEQ_CONFLICT", "读数序号已存在: " + request.seqNo());
        }
        return new GasReadingView(readingId, orderId, request.seqNo(), request.concentration(),
                request.measuredAt(), "RECORDED", false);
    }

    /**
     * 解除确认：两名不同复核人最终确认时重查最新读数与版本，
     * 原子结束散气、释放仓锁与风机；任一校验失败整体回滚，均不释放，失败不占幂等键。
     */
    @Transactional
    public ConfirmationView confirmRelease(long orderId, ReleaseConfirmRequest request) {
        String fingerprint = Fingerprints.structured(request.requestId(), orderId, request.reviewer(),
                request.expectedVersion());

        Optional<ConfirmationRow> existing = findConfirmationByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayConfirmationOrConflict(existing.get(), fingerprint);
        }

        OrderRow order = lockOrder(orderId);

        Optional<ConfirmationRow> rechecked = findConfirmationByRequestId(request.requestId());
        if (rechecked.isPresent()) {
            return replayConfirmationOrConflict(rechecked.get(), fingerprint);
        }
        if (order.status() != FumigationStatus.AERATING) {
            throw ApiException.unprocessable("INVALID_ORDER_STATE",
                    "仅散气中熏蒸单可解除，当前: " + order.status());
        }
        requireVersion(order, request.expectedVersion());
        Integer sameReviewer = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_confirmation WHERE order_id = ? AND reviewer = ?",
                Integer.class, orderId, request.reviewer());
        if (sameReviewer != null && sameReviewer > 0) {
            throw ApiException.unprocessable("SAME_REVIEWER", "复核人不得重复: " + request.reviewer());
        }

        int confirmationsBefore = countRows("fumigation_confirmation", orderId);
        int newVersion = order.version() + 1;
        boolean willRelease = confirmationsBefore + 1 >= REQUIRED_CONFIRMATIONS;
        if (willRelease) {
            // 重查最新读数：持单锁与采样串行，高浓度补录竞争不得用旧三点放行
            ReleaseAssessment assessment = assess(orderId, order.safeThreshold());
            if (!assessment.releasable()) {
                throw ApiException.unprocessable("RELEASE_CONDITION_NOT_MET",
                        "解除条件未满足: " + assessment.reason());
            }
        }

        try {
            siloService.insert(
                    "INSERT INTO fumigation_confirmation (order_id, request_id, reviewer, version_after,"
                            + " released, fingerprint) VALUES (?, ?, ?, ?, ?, ?)",
                    orderId, request.requestId(), request.reviewer(), newVersion, willRelease, fingerprint);
        } catch (DuplicateKeyException duplicate) {
            Optional<ConfirmationRow> byRequest = findConfirmationByRequestId(request.requestId());
            if (byRequest.isPresent()) {
                return replayConfirmationOrConflict(byRequest.get(), fingerprint);
            }
            throw ApiException.unprocessable("SAME_REVIEWER", "复核人不得重复: " + request.reviewer());
        }

        if (willRelease) {
            // 原子结束散气、释放仓锁与风机
            jdbc.update("UPDATE fumigation_order SET status = 'RELEASED', version = ?,"
                    + " released_at = CURRENT_TIMESTAMP WHERE id = ?", newVersion, orderId);
            jdbc.update("UPDATE fan SET status = 'IDLE', locked_fumigation_id = NULL"
                    + " WHERE locked_fumigation_id = ?", orderId);
            jdbc.update("UPDATE silo SET status = 'MONITORING' WHERE id = ?", order.siloId());
        } else {
            jdbc.update("UPDATE fumigation_order SET version = ? WHERE id = ?", newVersion, orderId);
        }
        return new ConfirmationView(orderId, request.reviewer(), confirmationsBefore + 1, willRelease,
                newVersion, false);
    }

    /**
     * 熏蒸单详情（含解除条件评估），供解除前查询。
     */
    public FumigationDetailView getOrder(long orderId) {
        OrderRow order = findOrderById(orderId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId));
        List<String> approvers = jdbc.query(
                "SELECT approver FROM fumigation_approval WHERE order_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getString("approver"), orderId);
        List<String> reviewers = jdbc.query(
                "SELECT reviewer FROM fumigation_confirmation WHERE order_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getString("reviewer"), orderId);
        return new FumigationDetailView(toView(order, false), approvers, reviewers,
                assess(orderId, order.safeThreshold()));
    }

    /**
     * 散气读数列表（按序号排序，可复算连续三点）。
     */
    public List<GasReadingView> readings(long orderId) {
        findOrderById(orderId).orElseThrow(
                () -> ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId));
        return jdbc.query("SELECT * FROM gas_reading WHERE order_id = ? ORDER BY seq_no",
                (rs, rowNum) -> new GasReadingView(rs.getLong("id"), orderId, rs.getInt("seq_no"),
                        rs.getBigDecimal("concentration"), rs.getTimestamp("measured_at").toLocalDateTime(),
                        "RECORDED", false), orderId);
    }

    /**
     * 粮仓熏蒸窗口列表（UTC 半开区间 [start, end)，可复算占用）。
     */
    public List<FumigationView> windowsOfSilo(long siloId) {
        siloService.getSilo(siloId);
        return jdbc.query("SELECT * FROM fumigation_order WHERE silo_id = ? ORDER BY window_start, id",
                FumigationService::mapOrder, siloId).stream().map(row -> toView(row, false)).toList();
    }

    /**
     * 解除条件评估：最新三点序号连续（缺号不视为安全）、浓度均不超过阈值（恰等合法、越限打断）、
     * 首末间隔≥30分钟（恰等合法）。
     */
    private ReleaseAssessment assess(long orderId, BigDecimal threshold) {
        List<GasRow> lastThree = jdbc.query(
                "SELECT * FROM gas_reading WHERE order_id = ? ORDER BY seq_no DESC LIMIT 3",
                FumigationService::mapGas, orderId);
        if (lastThree.size() < 3) {
            return new ReleaseAssessment(false, "读数不足三点", null, null, null, null);
        }
        GasRow latest = lastThree.get(0);
        GasRow middle = lastThree.get(1);
        GasRow first = lastThree.get(2);
        if (middle.seqNo() != latest.seqNo() - 1 || first.seqNo() != latest.seqNo() - 2) {
            return new ReleaseAssessment(false, "最新三点序号不连续（缺号不视为安全）", null, null, null, null);
        }
        if (first.concentration().compareTo(threshold) > 0
                || middle.concentration().compareTo(threshold) > 0
                || latest.concentration().compareTo(threshold) > 0) {
            return new ReleaseAssessment(false, "最新三点存在越限读数（连续计数被打断）", null, null, null, null);
        }
        Duration span = Duration.between(first.measuredAt(), latest.measuredAt());
        if (span.compareTo(Duration.ofMinutes(MIN_RELEASE_SPAN_MINUTES)) < 0) {
            return new ReleaseAssessment(false, "首末间隔不足30分钟", null, null, null, null);
        }
        return new ReleaseAssessment(true, "连续三点达标且首末间隔满足30分钟",
                first.seqNo(), latest.seqNo(), first.measuredAt(), latest.measuredAt());
    }

    private void requireVersion(OrderRow order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.unprocessable("VERSION_CONFLICT",
                    "版本不一致：期望 " + expectedVersion + "，当前 " + order.version());
        }
    }

    private int countRows(String table, long orderId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE order_id = ?",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private boolean findLateEvent(String requestId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM late_event WHERE request_id = ?",
                Integer.class, requestId);
        return count != null && count > 0;
    }

    private static ApiException idempotencyConflict(String requestId) {
        return ApiException.conflict("IDEMPOTENCY_CONFLICT",
                "requestId 已存在但业务字段不一致: " + requestId);
    }

    private FumigationView replayOrderOrConflict(OrderRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw idempotencyConflict(row.requestId());
        }
        return toView(row, true);
    }

    private ApprovalView replayApprovalOrConflict(ApprovalRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw idempotencyConflict(row.requestId());
        }
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_approval WHERE order_id = ? AND id <= ?",
                Integer.class, row.orderId(), row.id());
        return new ApprovalView(row.orderId(), row.approver(), approvals == null ? 0 : approvals,
                row.versionAfter(), FumigationStatus.valueOf(row.statusAfter()), true);
    }

    private GasReadingView replayGasOrConflict(GasRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw idempotencyConflict(row.requestId());
        }
        return new GasReadingView(row.id(), row.orderId(), row.seqNo(), row.concentration(),
                row.measuredAt(), "RECORDED", true);
    }

    private ConfirmationView replayConfirmationOrConflict(ConfirmationRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw idempotencyConflict(row.requestId());
        }
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_confirmation WHERE order_id = ? AND id <= ?",
                Integer.class, row.orderId(), row.id());
        return new ConfirmationView(row.orderId(), row.reviewer(), confirmations == null ? 0 : confirmations,
                row.released(), row.versionAfter(), true);
    }

    private SiloRow lockSilo(long siloId) {
        List<SiloRow> rows = jdbc.query("SELECT id, status FROM silo WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new SiloRow(rs.getLong("id"), rs.getString("status")), siloId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SILO_NOT_FOUND", "粮仓不存在: " + siloId);
        }
        return rows.get(0);
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM fumigation_order WHERE id = ? FOR UPDATE",
                FumigationService::mapOrder, orderId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("FUMIGATION_NOT_FOUND", "熏蒸单不存在: " + orderId);
        }
        return rows.get(0);
    }

    private Optional<OrderRow> findOrderByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_order WHERE request_id = ?",
                FumigationService::mapOrder, requestId).stream().findFirst();
    }

    private Optional<OrderRow> findOrderById(long orderId) {
        return jdbc.query("SELECT * FROM fumigation_order WHERE id = ?",
                FumigationService::mapOrder, orderId).stream().findFirst();
    }

    private Optional<ApprovalRow> findApprovalByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_approval WHERE request_id = ?",
                FumigationService::mapApproval, requestId).stream().findFirst();
    }

    private Optional<GasRow> findGasByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM gas_reading WHERE request_id = ?",
                FumigationService::mapGas, requestId).stream().findFirst();
    }

    private Optional<ConfirmationRow> findConfirmationByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM fumigation_confirmation WHERE request_id = ?",
                FumigationService::mapConfirmation, requestId).stream().findFirst();
    }

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(rs.getLong("id"), rs.getLong("silo_id"), rs.getString("request_id"),
                rs.getTimestamp("window_start").toLocalDateTime(),
                rs.getTimestamp("window_end").toLocalDateTime(),
                rs.getString("plan"), rs.getBigDecimal("safe_threshold"),
                FumigationStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                rs.getString("approver1"), rs.getString("approver2"),
                rs.getObject("fan_id", Long.class), rs.getObject("frozen_plan_version", Integer.class),
                rs.getString("start_request_id"), rs.getString("start_fingerprint"),
                rs.getString("finish_request_id"), rs.getString("finish_fingerprint"),
                rs.getString("fingerprint"),
                toLocalDateTime(rs, "started_at"), toLocalDateTime(rs, "finished_at"),
                toLocalDateTime(rs, "released_at"));
    }

    private static LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private static ApprovalRow mapApproval(ResultSet rs, int rowNum) throws SQLException {
        return new ApprovalRow(rs.getLong("id"), rs.getLong("order_id"), rs.getString("request_id"),
                rs.getString("approver"), rs.getInt("version_after"), rs.getString("status_after"),
                rs.getString("fingerprint"));
    }

    private static GasRow mapGas(ResultSet rs, int rowNum) throws SQLException {
        return new GasRow(rs.getLong("id"), rs.getLong("order_id"), rs.getString("request_id"),
                rs.getInt("seq_no"), rs.getBigDecimal("concentration"),
                rs.getTimestamp("measured_at").toLocalDateTime(), rs.getString("fingerprint"));
    }

    private static ConfirmationRow mapConfirmation(ResultSet rs, int rowNum) throws SQLException {
        return new ConfirmationRow(rs.getLong("id"), rs.getLong("order_id"), rs.getString("request_id"),
                rs.getString("reviewer"), rs.getInt("version_after"), rs.getBoolean("released"),
                rs.getString("fingerprint"));
    }

    private static FumigationView toView(OrderRow row, boolean replayed) {
        return new FumigationView(row.id(), row.siloId(), row.windowStart(), row.windowEnd(), row.plan(),
                row.safeThreshold(), row.status(), row.version(), row.approver1(), row.approver2(),
                row.fanId(), row.frozenPlanVersion(), row.startedAt(), row.finishedAt(), row.releasedAt(),
                replayed);
    }

    private record SiloRow(long id, String status) {
    }

    private record OrderRow(long id, long siloId, String requestId, LocalDateTime windowStart,
                            LocalDateTime windowEnd, String plan, BigDecimal safeThreshold,
                            FumigationStatus status, int version, String approver1, String approver2,
                            Long fanId, Integer frozenPlanVersion, String startRequestId,
                            String startFingerprint, String finishRequestId, String finishFingerprint,
                            String fingerprint, LocalDateTime startedAt, LocalDateTime finishedAt,
                            LocalDateTime releasedAt) {
    }

    private record ApprovalRow(long id, long orderId, String requestId, String approver, int versionAfter,
                               String statusAfter, String fingerprint) {
    }

    private record GasRow(long id, long orderId, String requestId, int seqNo, BigDecimal concentration,
                          LocalDateTime measuredAt, String fingerprint) {
    }

    private record ConfirmationRow(long id, long orderId, String requestId, String reviewer,
                                   int versionAfter, boolean released, String fingerprint) {
    }
}
