package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.UnitStatus;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import com.example.starter.repository.BloodUnitRepository;
import com.example.starter.repository.IssueRecordRepository;
import com.example.starter.repository.ReleaseOrderRepository;
import com.example.starter.repository.ReleaseOrderRepository.ReleaseOrder;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成分血放行服务。
 *
 * <p>幂等：以 requestId 为键，指纹覆盖 requestId、条码与时间窗；同键同参返回首次结果，
 * 同键异参返回 409 且不改变库存状态。
 *
 * <p>并发：对同一条码的放行通过行锁串行化，只允许成功一次；放行扣减与发血记录同事务
 * 同生同灭。过期单位一律拒绝并标记过期报废；回传与过期判定竞态由行锁按时间先后形成
 * 唯一终态。
 */
@Service
public class ReleaseService {

    static final String OUTCOME_RELEASED = "RELEASED";
    static final String OUTCOME_REJECTED = "REJECTED";

    private final BloodUnitRepository units;
    private final ReleaseOrderRepository orders;
    private final IssueRecordRepository issues;
    private final Clock clock;

    public ReleaseService(BloodUnitRepository units, ReleaseOrderRepository orders,
                          IssueRecordRepository issues, Clock clock) {
        this.units = units;
        this.orders = orders;
        this.issues = issues;
        this.clock = clock;
    }

    /**
     * 处理放行请求。
     *
     * <p>拒绝路径（过期标记、拒绝留痕）需要随事务提交，因此业务冲突异常不回滚；
     * 其余异常（如数据访问失败）仍整体回滚，保证放行扣减与发血记录同生同灭。
     *
     * @throws ConflictException 同键异参、单位不可放行或已过期（409）
     * @throws NotFoundException 条码不存在（404）
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public ReleaseResponse release(ReleaseRequest request) {
        String fingerprint = fingerprint(request);

        Optional<ReleaseOrder> existing = orders.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        BloodUnit unit = units.findByBarcodeForUpdate(request.barcode())
                .orElseThrow(() -> new NotFoundException("条码不存在: " + request.barcode()));
        Instant now = Instant.now(clock);

        // 过期判定：任何放行请求中，过期单位一律拒绝并标记过期报废（终态不动）
        if (!unit.status().isTerminal() && unit.isExpiredAt(now)) {
            units.updateStatus(unit, UnitStatus.EXPIRED_DISCARDED, now);
            recordOrder(request, fingerprint, OUTCOME_REJECTED, "单位已过期，标记过期报废", now);
            throw new ConflictException("UNIT_EXPIRED", "单位已过效期，已标记过期报废",
                    UnitStatus.EXPIRED_DISCARDED);
        }

        if (unit.status() != UnitStatus.RELEASABLE) {
            String detail = switch (unit.status()) {
                case RELEASED -> "单位已放行，不允许重复放行";
                case DISCARDED -> "单位已报废（终态），不允许放行";
                case EXPIRED_DISCARDED -> "单位已过期报废（终态），不允许放行";
                default -> "单位当前状态不可放行: " + unit.status();
            };
            recordOrder(request, fingerprint, OUTCOME_REJECTED, detail, now);
            throw new ConflictException("UNIT_NOT_RELEASABLE", detail, unit.status());
        }

        // 原子扣减可发库存并生成发血记录：同一事务，同生同灭
        units.updateStatus(unit, UnitStatus.RELEASED, now);
        issues.insert(request.requestId(), request.barcode(), now);
        recordOrder(request, fingerprint, OUTCOME_RELEASED, null, now);
        return new ReleaseResponse(request.requestId(), request.barcode(),
                OUTCOME_RELEASED, UnitStatus.RELEASED, null);
    }

    /**
     * 幂等重放：同键同参返回首次结果；同键异参返回 409 且不改变库存状态。
     */
    private ReleaseResponse replay(ReleaseOrder order, String fingerprint) {
        UnitStatus currentStatus = units.findByBarcode(order.barcode())
                .map(BloodUnit::status)
                .orElse(null);
        if (!order.fingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 但请求参数不一致", currentStatus);
        }
        if (OUTCOME_RELEASED.equals(order.outcome())) {
            return new ReleaseResponse(order.requestId(), order.barcode(),
                    order.outcome(), currentStatus, order.detail());
        }
        // 首次结果为拒绝：重放返回相同的拒绝语义
        throw new ConflictException("RELEASE_REJECTED_REPLAY", order.detail(), currentStatus);
    }

    /**
     * 记录放行单；request_id 唯一约束冲突时按并发同键重放处理。
     */
    private void recordOrder(ReleaseRequest request, String fingerprint,
                             String outcome, String detail, Instant now) {
        try {
            orders.insert(request.requestId(), fingerprint, request.barcode(),
                    request.windowStart(), request.windowEnd(), outcome, detail, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 已并发登记", null);
        }
    }

    /**
     * 计算幂等指纹：SHA-256(requestId|barcode|windowStart|windowEnd)。
     */
    static String fingerprint(ReleaseRequest request) {
        String material = request.requestId() + "|" + request.barcode() + "|"
                + request.windowStart() + "|" + request.windowEnd();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
