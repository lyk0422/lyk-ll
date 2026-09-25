package com.example.starter.service;

import com.example.starter.domain.BloodIssue;
import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.ReleaseOrder;
import com.example.starter.domain.ReleaseStatus;
import com.example.starter.domain.RejectReason;
import com.example.starter.domain.TestResultCode;
import com.example.starter.domain.TestResultEvent;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.repository.BloodIssueRepository;
import com.example.starter.repository.BloodUnitRepository;
import com.example.starter.repository.ReleaseOrderRepository;
import com.example.starter.repository.TestResultEventRepository;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import com.example.starter.web.dto.TestResultRequest;
import com.example.starter.web.dto.UnitResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 血液成分检疫隔离与放行核心服务。
 *
 * <p>状态流转：PENDING → QUARANTINED（一项合格一项未回）→ RELEASABLE（双检合格）→ RELEASED；
 * 任一检测不合格 → SCRAPPED；超过效期边界 → EXPIRED。SCRAPPED/EXPIRED/RELEASED 为终态。
 * 所有时间判定使用 UTC 时刻，效期边界时刻（含）之前可放行，其后判定过期报废。
 * 同一单位的回传、过期判定与放行通过行锁串行化，保证唯一终态。
 */
@Service
public class BloodService {

    static final String REASON_TEST_FAILED = "TEST_FAILED";
    static final String REASON_MANUAL = "MANUAL";
    static final String REASON_EXPIRED = "EXPIRED";

    private final BloodUnitRepository unitRepository;
    private final TestResultEventRepository eventRepository;
    private final ReleaseOrderRepository orderRepository;
    private final BloodIssueRepository issueRepository;
    private final TimeProvider timeProvider;

    public BloodService(BloodUnitRepository unitRepository,
                        TestResultEventRepository eventRepository,
                        ReleaseOrderRepository orderRepository,
                        BloodIssueRepository issueRepository,
                        TimeProvider timeProvider) {
        this.unitRepository = unitRepository;
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.issueRepository = issueRepository;
        this.timeProvider = timeProvider;
    }

    /**
     * 入库：唯一条码，初始为待检。重复入库返回 409。
     */
    @Transactional
    public UnitResponse intake(IntakeRequest request) {
        Instant now = timeProvider.now();
        BloodUnit unit = new BloodUnit();
        unit.setBarcode(request.barcode());
        unit.setComponentType(request.componentType());
        unit.setStatus(UnitStatus.PENDING);
        unit.setExpiresAt(request.expiresAt());
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        try {
            unitRepository.insert(unit);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("条码已入库: " + request.barcode());
        }
        return UnitResponse.from(unit);
    }

    /**
     * 检测结果回传。初检/复检独立，允许乱序；终态后的回传只记录为迟到事件，不复活单位。
     */
    @Transactional
    public UnitResponse reportTestResult(TestResultRequest request) {
        Instant receivedAt = request.receivedAt() != null ? request.receivedAt() : timeProvider.now();
        BloodUnit unit = lockUnit(request.barcode());

        TestResultEvent event = new TestResultEvent();
        event.setBarcode(unit.getBarcode());
        event.setTestType(request.testType());
        event.setResult(request.result());
        event.setReceivedAt(receivedAt);
        event.setCreatedAt(timeProvider.now());

        // 终态单位：任何回传均为迟到事件，状态不复活
        if (unit.getStatus().isTerminal()) {
            event.setAccepted(false);
            eventRepository.insert(event);
            return UnitResponse.from(unit);
        }

        // 回传时刻已过效期边界：过期优先，合格回传不能逆转过期判定
        if (receivedAt.isAfter(unit.getExpiresAt())) {
            event.setAccepted(false);
            eventRepository.insert(event);
            markExpired(unit, receivedAt);
            persist(unit);
            return UnitResponse.from(unit);
        }

        // 同一检测项重复回传：只记录事件，不重复驱动状态
        if (slotFilled(unit, request.testType())) {
            event.setAccepted(false);
            eventRepository.insert(event);
            return UnitResponse.from(unit);
        }

        applyResult(unit, request.testType(), request.result(), receivedAt);
        event.setAccepted(true);
        eventRepository.insert(event);
        persist(unit);
        return UnitResponse.from(unit);
    }

    /**
     * 人工报废，与过期/检测不合格同为报废终态。
     */
    @Transactional
    public UnitResponse scrap(String barcode) {
        BloodUnit unit = lockUnit(barcode);
        if (unit.getStatus().isTerminal()) {
            return UnitResponse.from(unit);
        }
        unit.setStatus(UnitStatus.SCRAPPED);
        unit.setScrapReason(REASON_MANUAL);
        persist(unit);
        return UnitResponse.from(unit);
    }

    /**
     * 放行：requestId 幂等（指纹覆盖 requestId、条码、时间窗）。
     * 放行成功时库存扣减与发血记录在同一事务内同生同灭。
     */
    @Transactional
    public ReleaseResponse release(ReleaseRequest request) {
        validateWindow(request);
        String fingerprint = fingerprint(request);

        // 同键重放：原样返回首次结果，不触碰库存
        ReleaseOrder existing = orderRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            return resolveIdempotent(existing, fingerprint, request.requestId());
        }

        // 行锁串行化同一单位的所有放行/回传/过期判定
        BloodUnit unit = lockUnit(request.barcode());
        Instant actionTime = request.requestTime() != null ? request.requestTime() : timeProvider.now();

        // 先落库放行单（唯一约束兜底并发同键），冲突时本事务尚未改动库存
        ReleaseOrder order = newOrder(request, fingerprint);
        try {
            orderRepository.insert(order);
        } catch (DuplicateKeyException ex) {
            ReleaseOrder winner = orderRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> new ConflictException("放行请求冲突: " + request.requestId()));
            if (!fingerprint.equals(winner.getFingerprint())) {
                // 同键异参：409 且不改变库存状态（本事务尚未触碰库存，抛出异常即回滚）
                throw new ConflictException("相同 requestId 的放行参数不一致: " + request.requestId());
            }
            return replay(winner);
        }

        // 评估并执行：过期判定 / 状态校验 / 原子放行
        Decision decision = evaluate(unit, request, actionTime);
        if (decision.expired()) {
            markExpired(unit, actionTime);
            persist(unit);
            order.setStatus(ReleaseStatus.REJECTED);
            order.setRejectReason(RejectReason.EXPIRED);
            updateOrderOutcome(order);
        } else if (decision.success()) {
            BloodIssue issue = new BloodIssue();
            issue.setBarcode(unit.getBarcode());
            issue.setRequestId(request.requestId());
            issue.setIssuedAt(actionTime);
            issue.setExpiresAt(unit.getExpiresAt());
            issue.setCreatedAt(timeProvider.now());
            try {
                issueRepository.insert(issue);
            } catch (DuplicateKeyException ex) {
                // 唯一约束兜底：并发下已有其他请求放行成功，整笔回滚，调用方查询得到 RELEASED 真实状态
                throw new ConflictException("该成分已被其他放行请求发血: " + unit.getBarcode());
            }
            unit.setStatus(UnitStatus.RELEASED);
            unit.setUpdatedAt(actionTime);
            persist(unit);
            order.setStatus(ReleaseStatus.SUCCESS);
            order.setIssuedAt(actionTime);
            updateOrderOutcome(order);
        } else {
            order.setStatus(ReleaseStatus.REJECTED);
            order.setRejectReason(decision.reason());
            updateOrderOutcome(order);
        }

        return buildResponse(order, unit, false);
    }

    /**
     * 查询单位当前真实状态。
     */
    @Transactional(readOnly = true)
    public BloodUnit getUnit(String barcode) {
        return unitRepository.findByBarcode(barcode)
                .orElseThrow(() -> new NotFoundException("成分不存在: " + barcode));
    }

    // ------------------------------------------------------------------
    // 内部方法（置于类末尾）
    // ------------------------------------------------------------------

    private BloodUnit lockUnit(String barcode) {
        return unitRepository.findByBarcodeForUpdate(barcode)
                .orElseThrow(() -> new NotFoundException("成分不存在: " + barcode));
    }

    private void persist(BloodUnit unit) {
        unit.setUpdatedAt(timeProvider.now());
        int rows = unitRepository.compareAndUpdate(unit);
        if (rows == 0) {
            throw new IllegalStateException("库存状态已被并发修改: " + unit.getBarcode());
        }
        unit.setVersion(unit.getVersion() + 1);
    }

    private boolean slotFilled(BloodUnit unit, TestType type) {
        return type == TestType.INITIAL ? unit.getInitialResult() != null : unit.getRepeatResult() != null;
    }

    private void applyResult(BloodUnit unit, TestType type, TestResultCode code, Instant receivedAt) {
        if (type == TestType.INITIAL) {
            unit.setInitialResult(code);
            unit.setInitialReceivedAt(receivedAt);
        } else {
            unit.setRepeatResult(code);
            unit.setRepeatReceivedAt(receivedAt);
        }
        if (!code.isPass()) {
            // 任一项不合格直接报废（终态）
            unit.setStatus(UnitStatus.SCRAPPED);
            unit.setScrapReason(REASON_TEST_FAILED);
            return;
        }
        boolean initialPass = unit.getInitialResult() == TestResultCode.PASS;
        boolean repeatPass = unit.getRepeatResult() == TestResultCode.PASS;
        if (initialPass && repeatPass) {
            // 双检合格进入可放行
            unit.setStatus(UnitStatus.RELEASABLE);
        } else {
            // 一项合格、另一项未回，保持隔离
            unit.setStatus(UnitStatus.QUARANTINED);
        }
    }

    private void markExpired(BloodUnit unit, Instant at) {
        unit.setStatus(UnitStatus.EXPIRED);
        unit.setExpiredAt(at);
        unit.setScrapReason(REASON_EXPIRED);
    }

    private Decision evaluate(BloodUnit unit, ReleaseRequest request, Instant actionTime) {
        if (unit.getStatus() == UnitStatus.RELEASED) {
            return Decision.reject(RejectReason.UNIT_RELEASED);
        }
        if (unit.getStatus() == UnitStatus.SCRAPPED) {
            return Decision.reject(RejectReason.SCRAPPED);
        }
        if (unit.getStatus() == UnitStatus.EXPIRED) {
            return Decision.reject(RejectReason.EXPIRED);
        }
        // 非终态单位：效期优先判定，边界时刻（含）仍可放行
        if (actionTime.isAfter(unit.getExpiresAt())) {
            return Decision.EXPIRE;
        }
        if (actionTime.isBefore(request.windowStart()) || actionTime.isAfter(request.windowEnd())) {
            return Decision.reject(RejectReason.OUTSIDE_WINDOW);
        }
        if (unit.getStatus() == UnitStatus.RELEASABLE) {
            return Decision.SUCCESS;
        }
        return Decision.reject(RejectReason.NOT_RELEASABLE);
    }

    private void validateWindow(ReleaseRequest request) {
        if (request.windowEnd().isBefore(request.windowStart())) {
            throw new IllegalArgumentException("放行时间窗终点早于起点");
        }
    }

    private ReleaseOrder newOrder(ReleaseRequest request, String fingerprint) {
        ReleaseOrder order = new ReleaseOrder();
        order.setRequestId(request.requestId());
        order.setBarcode(request.barcode());
        order.setWindowStart(request.windowStart());
        order.setWindowEnd(request.windowEnd());
        order.setFingerprint(fingerprint);
        order.setCreatedAt(timeProvider.now());
        order.setStatus(ReleaseStatus.REJECTED);
        return order;
    }

    private void updateOrderOutcome(ReleaseOrder order) {
        // 放行单已插入，此处补齐结论（同事务内更新）
        orderRepository.updateOutcome(order);
    }

    private ReleaseResponse resolveIdempotent(ReleaseOrder existing, String fingerprint, String requestId) {
        if (!fingerprint.equals(existing.getFingerprint())) {
            throw new ConflictException("相同 requestId 的放行参数不一致: " + requestId);
        }
        return replay(existing);
    }

    private ReleaseResponse replay(ReleaseOrder order) {
        BloodUnit current = unitRepository.findByBarcode(order.getBarcode()).orElse(null);
        String unitStatus = current == null ? null : current.getStatus().name();
        return new ReleaseResponse(order.getRequestId(), order.getBarcode(), order.getStatus().name(),
                order.getRejectReason() == null ? null : order.getRejectReason().name(),
                unitStatus, order.getIssuedAt(), true);
    }

    private ReleaseResponse buildResponse(ReleaseOrder order, BloodUnit unit, boolean replay) {
        return new ReleaseResponse(order.getRequestId(), order.getBarcode(), order.getStatus().name(),
                order.getRejectReason() == null ? null : order.getRejectReason().name(),
                unit.getStatus().name(), order.getIssuedAt(), replay);
    }

    /**
     * 幂等指纹：SHA-256(requestId|barcode|windowStart|windowEnd)，参数以 UTC ISO-8601 表示。
     */
    static String fingerprint(ReleaseRequest request) {
        String raw = String.join("|",
                request.requestId(),
                request.barcode(),
                request.windowStart().toString(),
                request.windowEnd().toString());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 放行评估结论。
     */
    private record Decision(boolean success, boolean expired, RejectReason reason) {
        static final Decision SUCCESS = new Decision(true, false, null);
        static final Decision EXPIRE = new Decision(false, true, RejectReason.EXPIRED);

        static Decision reject(RejectReason reason) {
            return new Decision(false, false, reason);
        }
    }
}
