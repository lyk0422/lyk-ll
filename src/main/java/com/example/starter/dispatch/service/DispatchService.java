package com.example.starter.dispatch.service;

import com.example.starter.dispatch.api.DispatchException;
import com.example.starter.dispatch.api.GateStepView;
import com.example.starter.dispatch.api.IssueOrderRequest;
import com.example.starter.dispatch.api.NotificationView;
import com.example.starter.dispatch.api.OrderView;
import com.example.starter.dispatch.api.ReceiptRequest;
import com.example.starter.dispatch.domain.GateStepStatus;
import com.example.starter.dispatch.domain.NotificationType;
import com.example.starter.dispatch.domain.OrderStatus;
import com.example.starter.dispatch.repo.DispatchRepository;
import com.example.starter.dispatch.repo.DispatchRepository.GateSeed;
import com.example.starter.dispatch.repo.GateStepRow;
import com.example.starter.dispatch.repo.NotificationRow;
import com.example.starter.dispatch.repo.OrderRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HexFormat;

/**
 * 闸门调度令执行与回执服务。
 *
 * <p>关键并发语义均在行锁与条件更新内完成：
 * <ul>
 *   <li>下达：active_gate_lock 主键保证同一闸门同一时刻仅一张未完成令生效；</li>
 *   <li>签收/撤销：对调度令行加锁并按当前状态条件更新，形成唯一结果；</li>
 *   <li>逐门回执/超时升级：对调度令行加锁串行化，终态条件更新保证不会既超时又完成；</li>
 *   <li>下达、签收、回执按 requestId 幂等：指纹相同返回首次结果，相异返回 409。</li>
 * </ul>
 */
@Service
public class DispatchService {

    /** 到位允许偏差：±2%，恰在边界判为到位。 */
    static final BigDecimal POSITION_TOLERANCE = new BigDecimal("0.02");

    private final DispatchRepository repository;
    private final DispatchClock clock;
    private final ObjectMapper objectMapper;

    public DispatchService(DispatchRepository repository, DispatchClock clock,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderView issue(IssueOrderRequest request) {
        validateIssueRequest(request);
        long now = clock.now().toEpochMilli();

        OrderRow existing = repository.findOrder(request.requestId());
        if (existing != null) {
            String fingerprint = issueFingerprint(request);
            if (!fingerprint.equals(existing.fingerprint())) {
                throw DispatchException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务参数不一致");
            }
            return toView(existing, repository.findSteps(existing.requestId()));
        }

        List<GateSeed> seeds = new ArrayList<>();
        for (int i = 0; i < request.gates().size(); i++) {
            seeds.add(new GateSeed(i + 1, request.gates().get(i), request.targetOpenings().get(i)));
        }

        OrderRow order = new OrderRow(
                request.requestId(),
                issueFingerprint(request),
                OrderStatus.ISSUED.name(),
                request.deadline().toEpochMilli(),
                now, null, null, false, false, null,
                writePayload(seeds), now);
        try {
            // 主键先落库：拦截同 requestId 并发下达，落入幂等/冲突判定。
            repository.insertOrder(order, seeds);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            OrderRow concurrent = repository.findOrder(request.requestId());
            if (concurrent == null) {
                throw DispatchException.conflict("ISSUE_RACE", "调度令并发下达冲突，请重试查询");
            }
            if (!order.fingerprint().equals(concurrent.fingerprint())) {
                throw DispatchException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 已存在但业务参数不一致");
            }
            return toView(concurrent, repository.findSteps(request.requestId()));
        }

        // 逐闸占用；任一闸门已有未完成令则整单回滚，并发下达仅一张生效。
        for (GateSeed seed : seeds) {
            if (!repository.acquireGateLock(seed.gateCode(), request.requestId(), now)) {
                throw DispatchException.conflict("GATE_BUSY",
                        "闸门 " + seed.gateCode() + " 已存在未完成调度令");
            }
        }
        return toView(order, repository.findSteps(order.requestId()));
    }

    @Transactional
    public OrderView acknowledge(String requestId) {
        OrderRow order = repository.lockOrder(requestId);
        if (order == null) {
            throw DispatchException.notFound("调度令不存在: " + requestId);
        }
        long now = clock.now().toEpochMilli();
        switch (OrderStatus.valueOf(order.status())) {
            case ISSUED -> {
                repository.markAcknowledged(requestId, now);
                return reloadView(requestId);
            }
            // 此前已成功签收后的重放：返回首次结果，不改变状态。
            case ACKNOWLEDGED, COMPLETED, ABORTED -> {
                return toView(order, repository.findSteps(requestId));
            }
            case TIMEOUT_ESCALATED -> {
                if (order.acknowledgedAt() != null) {
                    return toView(order, repository.findSteps(requestId));
                }
                throw DispatchException.conflict("ORDER_ESCALATED",
                        "调度令已超时升级，不可签收");
            }
            case CANCELLED -> throw DispatchException.conflict("ORDER_CANCELLED",
                    "调度令已在签收前撤销，不可签收");
        }
        throw DispatchException.conflict("INVALID_STATE", "当前状态不可签收");
    }

    @Transactional
    public OrderView receipt(String requestId, ReceiptRequest request) {
        OrderRow order = repository.lockOrder(requestId);
        if (order == null) {
            throw DispatchException.notFound("调度令不存在: " + requestId);
        }
        GateStepRow step = repository.findStep(requestId, request.gateCode());
        if (step == null) {
            throw DispatchException.badRequest("GATE_NOT_IN_ORDER",
                    "闸门 " + request.gateCode() + " 不在调度令中");
        }

        String fingerprint = receiptFingerprint(requestId, request);
        OrderStatus status = OrderStatus.valueOf(order.status());

        if (step.status().equals(GateStepStatus.RECEIVED.name())) {
            // 同键同参回执重放：返回首次结果；同键异参返回 409 且不改状态。
            if (!fingerprint.equals(step.receiptFingerprint())) {
                throw DispatchException.conflict("RECEIPT_CONFLICT",
                        "闸门 " + request.gateCode() + " 已有内容不一致的回执");
            }
            return toView(order, repository.findSteps(requestId));
        }

        switch (status) {
            case ACKNOWLEDGED -> {
                // 顺序执行：前一门未回执完成，本门不得启动。
                if (step.seq() > 1) {
                    List<GateStepRow> steps = repository.findSteps(requestId);
                    GateStepRow previous = steps.get(step.seq() - 2);
                    if (!previous.status().equals(GateStepStatus.RECEIVED.name())) {
                        throw DispatchException.conflict("GATE_NOT_IN_SEQUENCE",
                                "前序闸门 " + previous.gateCode() + " 尚未回执完成，不得执行本门");
                    }
                }
            }
            case TIMEOUT_ESCALATED -> throw DispatchException.conflict("ORDER_ESCALATED",
                    "调度令已超时升级，迟到回执不得改变终态");
            case CANCELLED -> throw DispatchException.conflict("ORDER_CANCELLED",
                    "调度令已撤销，不再接收回执");
            case ABORTED -> throw DispatchException.conflict("ORDER_ABORTED",
                    "调度令已中止，不再接收回执");
            case COMPLETED -> throw DispatchException.conflict("ORDER_COMPLETED",
                    "调度令已完成");
            case ISSUED -> throw DispatchException.conflict("NOT_ACKNOWLEDGED",
                    "调度令尚未签收，不得回执");
        }

        Deviation deviation = evaluate(step.targetOpening(), request.actualOpening());
        long completedAt = request.completedAt().toEpochMilli();
        int updated = repository.recordReceipt(requestId, step.seq(),
                request.actualOpening(), deviation.value(), deviation.inPosition(),
                completedAt, fingerprint);
        if (updated == 0) {
            // 并发下该执行项已被其他事务写入：交由重放/冲突分支处理。
            throw DispatchException.conflict("RECEIPT_RACE", "回执并发冲突，请重试查询");
        }

        if (!deviation.inPosition()) {
            String message = "闸门 " + request.gateCode() + " 实际开度 "
                    + request.actualOpening().toPlainString() + "% 与目标 "
                    + step.targetOpening().toPlainString() + "% 偏差 "
                    + deviation.displayPercent() + "% 超出 ±2%";
            repository.insertNotification(requestId,
                    NotificationType.DEVIATION_ALARM.name(), message, clock.now().toEpochMilli());
        }

        if (repository.countPendingSteps(requestId) == 0) {
            int completed = repository.markCompleted(requestId, clock.now().toEpochMilli());
            if (completed == 1) {
                repository.releaseGateLocks(requestId);
            }
        }
        return reloadView(requestId);
    }

    @Transactional
    public OrderView cancel(String requestId) {
        OrderRow order = repository.lockOrder(requestId);
        if (order == null) {
            throw DispatchException.notFound("调度令不存在: " + requestId);
        }
        switch (OrderStatus.valueOf(order.status())) {
            case ISSUED -> {
                repository.markCancelled(requestId, clock.now().toEpochMilli());
                repository.releaseGateLocks(requestId);
                return reloadView(requestId);
            }
            case CANCELLED -> {
                return toView(order, repository.findSteps(requestId));
            }
            case ACKNOWLEDGED -> throw DispatchException.conflict("ALREADY_ACKNOWLEDGED",
                    "调度令已签收，不可撤销，只能中止");
            case COMPLETED, TIMEOUT_ESCALATED, ABORTED -> throw DispatchException.conflict(
                    "ORDER_TERMINAL", "调度令已终态，不可撤销");
        }
        throw DispatchException.conflict("INVALID_STATE", "当前状态不可撤销");
    }

    @Transactional
    public OrderView abort(String requestId, String note) {
        OrderRow order = repository.lockOrder(requestId);
        if (order == null) {
            throw DispatchException.notFound("调度令不存在: " + requestId);
        }
        String trimmedNote = note == null || note.isBlank() ? "人工中止" : note.trim();
        switch (OrderStatus.valueOf(order.status())) {
            case ACKNOWLEDGED -> {
                repository.markAborted(requestId, clock.now().toEpochMilli(), trimmedNote);
                repository.releaseGateLocks(requestId);
                return reloadView(requestId);
            }
            case ABORTED -> {
                return toView(order, repository.findSteps(requestId));
            }
            case ISSUED -> throw DispatchException.conflict("NOT_ACKNOWLEDGED",
                    "调度令未签收，应使用撤销而非中止");
            case CANCELLED -> throw DispatchException.conflict("ORDER_CANCELLED",
                    "调度令已撤销，不可中止");
            case COMPLETED, TIMEOUT_ESCALATED -> throw DispatchException.conflict(
                    "ORDER_TERMINAL", "调度令已终态，不可中止");
        }
        throw DispatchException.conflict("INVALID_STATE", "当前状态不可中止");
    }

    @Transactional
    public OrderView takeOver(String requestId) {
        OrderRow order = repository.lockOrder(requestId);
        if (order == null) {
            throw DispatchException.notFound("调度令不存在: " + requestId);
        }
        if (order.escalated()) {
            repository.markTakenOver(requestId);
            return reloadView(requestId);
        }
        throw DispatchException.conflict("NOT_ESCALATED", "调度令未超时升级，不可接管");
    }

    @Transactional(readOnly = true)
    public OrderView getOrder(String requestId) {
        OrderRow order = repository.findOrder(requestId);
        if (order == null) {
            throw DispatchException.notFound("调度令不存在: " + requestId);
        }
        return toView(order, repository.findSteps(requestId));
    }

    @Transactional(readOnly = true)
    public List<OrderView> listOrders() {
        List<OrderView> views = new ArrayList<>();
        for (OrderRow order : repository.findAllOrders()) {
            views.add(toView(order, repository.findSteps(order.requestId())));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<NotificationView> listNotifications(String requestId) {
        if (repository.findOrder(requestId) == null) {
            throw DispatchException.notFound("调度令不存在: " + requestId);
        }
        List<NotificationView> views = new ArrayList<>();
        for (NotificationRow row : repository.findNotifications(requestId)) {
            views.add(new NotificationView(row.id(), row.requestId(),
                    NotificationType.valueOf(row.type()), row.message(),
                    Instant.ofEpochMilli(row.createdAt())));
        }
        return views;
    }

    /**
     * 超时扫描：对已签收且超过执行时限仍未终态的令升级并立即通知上级。
     * 与最后一门回执在调度令行锁上串行化，终态条件更新保证唯一终态。
     *
     * @return 本次新升级的令数
     */
    @Transactional
    public int sweepTimeouts() {
        long now = clock.now().toEpochMilli();
        int escalated = 0;
        for (OrderRow candidate : repository.findTimeoutCandidates(now)) {
            OrderRow locked = repository.lockOrder(candidate.requestId());
            String lockedStatus = locked == null ? null : locked.status();
            if (locked == null
                    || (!OrderStatus.ISSUED.name().equals(lockedStatus)
                        && !OrderStatus.ACKNOWLEDGED.name().equals(lockedStatus))
                    || locked.deadline() >= now) {
                continue;
            }
            int rows = repository.markTimeoutEscalated(candidate.requestId(), now);
            if (rows == 1) {
                repository.releaseGateLocks(candidate.requestId());
                repository.insertNotification(candidate.requestId(),
                        NotificationType.ESCALATION.name(),
                        "调度令超过执行时限未收齐回执，已超时升级，请上级立即处置",
                        now);
                escalated++;
            }
        }
        return escalated;
    }

    private void validateIssueRequest(IssueOrderRequest request) {
        if (request.gates().size() != request.targetOpenings().size()) {
            throw DispatchException.badRequest("SIZE_MISMATCH",
                    "闸门列表与目标开度序列长度必须一致");
        }
        if (new LinkedHashSet<>(request.gates()).size() != request.gates().size()) {
            throw DispatchException.badRequest("DUPLICATE_GATE",
                    "同一调度令内闸门不得重复");
        }
        for (BigDecimal opening : request.targetOpenings()) {
            if (opening.compareTo(BigDecimal.ZERO) < 0
                    || opening.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw DispatchException.badRequest("INVALID_OPENING",
                        "目标开度须在 0-100 之间: " + opening.toPlainString());
            }
        }
    }

    private OrderView reloadView(String requestId) {
        return toView(repository.findOrder(requestId), repository.findSteps(requestId));
    }

    private OrderView toView(OrderRow order, List<GateStepRow> steps) {
        List<GateStepView> stepViews = steps.stream().map(this::toStepView).toList();
        return new OrderView(
                order.requestId(),
                OrderStatus.valueOf(order.status()),
                Instant.ofEpochMilli(order.deadline()),
                Instant.ofEpochMilli(order.issuedAt()),
                order.acknowledgedAt() == null ? null : Instant.ofEpochMilli(order.acknowledgedAt()),
                order.finishedAt() == null ? null : Instant.ofEpochMilli(order.finishedAt()),
                order.escalated(),
                order.takenOver(),
                stepViews);
    }

    private GateStepView toStepView(GateStepRow step) {
        Boolean inPosition = step.inPosition();
        return new GateStepView(
                step.seq(),
                step.gateCode(),
                step.targetOpening(),
                step.actualOpening(),
                step.deviation(),
                inPosition,
                step.completedAt() == null ? null : Instant.ofEpochMilli(step.completedAt()),
                GateStepStatus.valueOf(step.status()));
    }

    /**
     * 到位判定：相对偏差 |实际-目标|/目标 ≤ 2%（含边界）；目标开度为 0 时仅完全关闭判到位。
     */
    static Deviation evaluate(BigDecimal target, BigDecimal actual) {
        BigDecimal absDiff = actual.subtract(target).abs();
        if (target.compareTo(BigDecimal.ZERO) == 0) {
            boolean inPosition = absDiff.compareTo(BigDecimal.ZERO) == 0;
            return new Deviation(absDiff, inPosition,
                    inPosition ? BigDecimal.ZERO : new BigDecimal("100.00"));
        }
        BigDecimal relative = absDiff.divide(target, 6, RoundingMode.HALF_UP);
        boolean inPosition = relative.compareTo(POSITION_TOLERANCE) <= 0;
        BigDecimal percent = relative.multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
        return new Deviation(relative, inPosition, percent);
    }

    private String issueFingerprint(IssueOrderRequest request) {
        StringBuilder canonical = new StringBuilder(request.requestId()).append('|')
                .append(request.deadline().toEpochMilli()).append('|')
                .append(String.join(",", request.gates())).append('|');
        for (int i = 0; i < request.targetOpenings().size(); i++) {
            if (i > 0) {
                canonical.append(',');
            }
            canonical.append(request.targetOpenings().get(i).stripTrailingZeros().toPlainString());
        }
        return sha256(canonical.toString());
    }

    private String receiptFingerprint(String requestId, ReceiptRequest request) {
        return sha256(requestId + '|' + request.gateCode() + '|'
                + request.actualOpening().stripTrailingZeros().toPlainString() + '|'
                + request.completedAt().toEpochMilli());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writePayload(List<GateSeed> seeds) {
        try {
            return objectMapper.writeValueAsString(seeds.stream()
                    .map(s -> new PayloadGate(s.seq(), s.gateCode(), s.targetOpening()))
                    .toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 下达业务字段快照条目。 */
    private record PayloadGate(int seq, String gateCode, BigDecimal targetOpening) {
    }

    /** 偏差计算结果：value 为存储用偏差，displayPercent 为百分比展示值。 */
    record Deviation(BigDecimal value, boolean inPosition, BigDecimal displayPercent) {
    }
}
