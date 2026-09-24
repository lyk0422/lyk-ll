package com.example.starter.blood.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.blood.domain.BloodUnit;
import com.example.starter.blood.domain.ReleaseRequestLog;
import com.example.starter.blood.domain.UnitStatus;
import com.example.starter.blood.repo.BloodUnitRepository;
import com.example.starter.blood.repo.IssueRecordRepository;
import com.example.starter.blood.repo.ReleaseRequestRepository;

/**
 * 放行服务：requestId 幂等、效期判定、原子扣减可发库存并生成发血记录。
 */
@Service
public class ReleaseService {

    /** 放行成功。 */
    public static final String OUTCOME_RELEASED = "RELEASED";
    /** 放行被拒绝。 */
    public static final String OUTCOME_REJECTED = "REJECTED";

    private final BloodUnitRepository unitRepository;
    private final ReleaseRequestRepository releaseRepository;
    private final IssueRecordRepository issueRepository;
    private final Clock clock;

    public ReleaseService(BloodUnitRepository unitRepository, ReleaseRequestRepository releaseRepository,
                          IssueRecordRepository issueRepository, Clock clock) {
        this.unitRepository = unitRepository;
        this.releaseRepository = releaseRepository;
        this.issueRepository = issueRepository;
        this.clock = clock;
    }

    /**
     * 处理放行请求。同键同参返回首次结果；同键异参返回 409 且不改变库存状态；
     * 过期单位一律拒绝并标记过期报废；放行成功与发血记录同事务写入。
     *
     * @param requestId   幂等键
     * @param barcode     目标条码
     * @param windowStart 放行时间窗起点（UTC）
     * @param windowEnd   放行时间窗终点（UTC）
     * @return 放行处理结果
     */
    @Transactional
    public ReleaseResult release(String requestId, String barcode, Instant windowStart, Instant windowEnd) {
        String fingerprint = fingerprint(requestId, barcode, windowStart, windowEnd);
        var existing = releaseRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        BloodUnit unit = unitRepository.findByBarcodeForUpdate(barcode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "条码不存在: " + barcode));
        Instant now = clock.instant();

        String outcome;
        UnitStatus unitStatus;
        String message;
        if (now.isAfter(unit.expireAt())) {
            // 过期判定：一律拒绝并标记过期报废（终态单位不受影响），以行锁与回传串行化形成唯一终态
            unitRepository.markExpiredDiscarded(barcode, now);
            unitStatus = unitRepository.findByBarcode(barcode).map(BloodUnit::status).orElse(unit.status());
            outcome = OUTCOME_REJECTED;
            message = "单位已过效期（UTC），禁止放行";
        } else if (unit.status() == UnitStatus.RELEASABLE) {
            int deducted = unitRepository.compareAndSetStatus(barcode, UnitStatus.RELEASABLE,
                    UnitStatus.RELEASED, now);
            if (deducted == 1) {
                // 库存扣减与发血记录同一事务，同生同灭
                issueRepository.insert(requestId, barcode, windowStart, windowEnd, now);
                outcome = OUTCOME_RELEASED;
                unitStatus = UnitStatus.RELEASED;
                message = null;
            } else {
                unitStatus = unitRepository.findByBarcode(barcode).map(BloodUnit::status).orElse(unit.status());
                outcome = OUTCOME_REJECTED;
                message = "可发库存已被并发扣减";
            }
        } else {
            outcome = OUTCOME_REJECTED;
            unitStatus = unit.status();
            message = "当前状态不可放行: " + unit.status();
        }

        ReleaseRequestLog log = new ReleaseRequestLog(requestId, fingerprint, barcode, outcome, unitStatus,
                message, now);
        try {
            releaseRepository.insert(log);
        } catch (DuplicateKeyException e) {
            // 并发同键：主键约束保证唯一，重读首次结果并按指纹判定
            ReleaseRequestLog raced = releaseRepository.findByRequestId(requestId).orElseThrow();
            return replayOrConflict(raced, fingerprint);
        }
        return new ReleaseResult(requestId, outcome, unitStatus, message, false);
    }

    private ReleaseResult replayOrConflict(ReleaseRequestLog log, String fingerprint) {
        if (!log.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "requestId 已存在但参数指纹不一致: " + log.requestId());
        }
        return new ReleaseResult(log.requestId(), log.outcome(), log.unitStatus(), log.message(), true);
    }

    /**
     * 计算幂等指纹：覆盖 requestId、条码与放行时间窗。
     *
     * @param requestId   幂等键
     * @param barcode     条码
     * @param windowStart 时间窗起点
     * @param windowEnd   时间窗终点
     * @return SHA-256 十六进制指纹
     */
    static String fingerprint(String requestId, String barcode, Instant windowStart, Instant windowEnd) {
        String material = requestId + '|' + barcode + '|' + windowStart + '|' + windowEnd;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
