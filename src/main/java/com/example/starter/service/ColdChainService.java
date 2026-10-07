package com.example.starter.service;

import com.example.starter.domain.ColdChainLedger;
import com.example.starter.domain.ColdChainReading;
import com.example.starter.domain.ColdChainSegment;
import com.example.starter.domain.ColdChainStatus;
import com.example.starter.domain.ColdChainViolation;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewEvent;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.ColdChainLedgerView;
import com.example.starter.dto.ColdChainReadingView;
import com.example.starter.dto.ColdChainSegmentView;
import com.example.starter.dto.ColdChainSubmitRequest;
import com.example.starter.dto.ColdChainViolationView;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.ReadingInput;
import com.example.starter.dto.SegmentInput;
import com.example.starter.dto.ServiceResult;
import com.example.starter.idempotency.Fingerprints;
import com.example.starter.json.JsonSupport;
import com.example.starter.repo.ColdChainRepository;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.ReviewRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 冷链运输片段核验服务：受控包装子批的运输证据登记、补录/更正与台账查询。
 *
 * <p>证据按版本整体冻结：登记版本 1，补录/更正携带 expectedVersion、成功加 1、旧版 409；
 * 每次提交对完整证据重核，合格即冻结该版本温证，不合格则台账记录缺口/越界点并置
 * UNQUALIFIED（缺样本只记缺口，不按零度补齐），签发门禁只信当前温证。
 * 签发后补录/更正仅登记风险事件，不回改旧证书及核销数量。
 * 证据、温证与幂等结果同事务；业务失败（4xx 异常）回滚不占 requestId 键。
 */
@Service
public class ColdChainService {

    private final ColdChainRepository coldChainRepository;
    private final ReviewRepository reviewRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final JsonSupport jsonSupport;
    private final TransactionTemplate txTemplate;

    public ColdChainService(ColdChainRepository coldChainRepository,
                            ReviewRepository reviewRepository,
                            IdempotencyRepository idempotencyRepository,
                            JsonSupport jsonSupport,
                            TransactionTemplate txTemplate) {
        this.coldChainRepository = coldChainRepository;
        this.reviewRepository = reviewRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.jsonSupport = jsonSupport;
        this.txTemplate = txTemplate;
    }

    // ============================ 证据提交（登记 / 补录 / 更正） ============================

    /**
     * 提交运输证据（requestId 幂等）。首次登记 expectedVersion=0（版本 1 起）；
     * 补录/更正携带当前版本，成功加 1 并使旧温证资格失效（以新版本重核结果为准）。
     * 证据不合格不拒绝写入：台账记录缺口/越界点并置 UNQUALIFIED，签发门禁据此拒签。
     */
    public ServiceResult submit(String batchNo, ColdChainSubmitRequest request) {
        String fingerprint = Fingerprints.coldchainSubmit(batchNo, request);
        return idempotent(request.requestId(), Fingerprints.COLDCHAIN_SUBMIT, fingerprint,
                () -> doSubmit(batchNo, request));
    }

    private ServiceResult doSubmit(String batchNo, ColdChainSubmitRequest request) {
        Instant now = request.submittedAt();
        // 加锁顺序保持 batch_review → coldchain_ledger（签发入口同样先锁审查行再锁台账）。
        Review review = reviewRepository.lockByBatchNo(batchNo.trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
        }
        ColdChainLedger ledger = coldChainRepository.lockByReviewId(review.getId());

        if (review.getStatus() == ReviewStatus.ISSUED) {
            // 签发先成的竞争落败方：仅登记风险事件，不回改旧证书、版本与核销数量。
            if (ledger == null) {
                throw new BusinessException(422, "COLDCHAIN_REGISTRATION_CLOSED",
                        "子批已签发归档且未启用冷链，不再登记运输证据: " + review.getBatchNo());
            }
            reviewRepository.insertEvent(new ReviewEvent(review.getId(),
                    "COLDCHAIN_SUPPLEMENT_AFTER_ISSUE",
                    jsonSupport.write(Map.of(
                            "risk", "签发归档后收到补录/更正，旧温证与核销数量保持不变",
                            "version", ledger.getVersion(),
                            "certVersion", ledger.getCertVersion() == null ? 0 : ledger.getCertVersion())),
                    now));
            return ServiceResult.ok(toView(ledger));
        }

        if (ledger == null && request.expectedVersion() != 0) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "冷链台账尚未登记，首次登记期望版本须为 0，实际 " + request.expectedVersion());
        }
        if (ledger != null && ledger.getVersion() != request.expectedVersion()) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "冷链台账版本已变化，期望 " + request.expectedVersion()
                            + "，当前 " + ledger.getVersion());
        }

        // 完整证据重核：缺端点、缺片、缺序号、间隔超限、越界逐点收集，不按平均温度掩盖。
        List<ColdChainVerifier.Gap> gaps = ColdChainVerifier.verify(request.segments(), now);
        boolean qualified = gaps.isEmpty();
        int newVersion = ledger == null ? 1 : ledger.getVersion() + 1;

        Instant transportStart = request.segments().get(0).start();
        Instant transportEnd = request.segments().get(request.segments().size() - 1).end();

        if (ledger == null) {
            ledger = new ColdChainLedger();
            ledger.setReviewId(review.getId());
            ledger.setBatchNo(review.getBatchNo());
            ledger.setCreatedAt(now);
            applyVersion(ledger, newVersion, qualified, transportStart, transportEnd, now);
            Long ledgerId = coldChainRepository.insertLedger(ledger);
            if (ledgerId == null) {
                // 并发首次登记落败（uk_coldchain_review）：按版本冲突处理，调用方刷新重试。
                throw new BusinessException(409, "VERSION_CONFLICT",
                        "冷链台账已被并发登记，请刷新版本后重试: " + review.getBatchNo());
            }
            ledger.setId(ledgerId);
        } else {
            applyVersion(ledger, newVersion, qualified, transportStart, transportEnd, now);
            coldChainRepository.updateLedger(ledger);
        }

        freezeEvidence(ledger, newVersion, request.segments(), gaps, now);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "COLDCHAIN_EVIDENCE_SUBMITTED",
                jsonSupport.write(Map.of(
                        "version", newVersion,
                        "qualified", qualified,
                        "violationCount", gaps.size())), now));
        return ServiceResult.ok(toView(ledger));
    }

    /** 把本次核验结果应用到台账头：版本前进，合格则冻结温证版本，不合格则旧温证资格失效。 */
    private void applyVersion(ColdChainLedger ledger, int newVersion, boolean qualified,
                              Instant transportStart, Instant transportEnd, Instant now) {
        ledger.setVersion(newVersion);
        ledger.setStatus(qualified ? ColdChainStatus.QUALIFIED : ColdChainStatus.UNQUALIFIED);
        if (qualified) {
            ledger.setCertVersion(newVersion);
            ledger.setQualifiedAt(now);
        }
        // 不合格时 certVersion 停留在历史合格版本：因不等于当前版本，旧温证资格已失效。
        ledger.setTransportStart(transportStart);
        ledger.setTransportEnd(transportEnd);
        ledger.setSubmittedAt(now);
        ledger.setUpdatedAt(now);
    }

    /** 按版本整体冻结片段、读数与核验缺口（旧版本行不回改）。 */
    private void freezeEvidence(ColdChainLedger ledger, int version, List<SegmentInput> segments,
                                List<ColdChainVerifier.Gap> gaps, Instant now) {
        for (SegmentInput input : segments) {
            ColdChainSegment segment = new ColdChainSegment();
            segment.setLedgerId(ledger.getId());
            segment.setVersion(version);
            segment.setSeq(input.seq());
            segment.setStartTs(input.start());
            segment.setEndTs(input.end());
            segment.setCreatedAt(now);
            Long segmentId = coldChainRepository.insertSegment(segment);

            List<ColdChainReading> readings = new ArrayList<>();
            for (ReadingInput readingInput : input.readings()) {
                ColdChainReading reading = new ColdChainReading();
                reading.setSegmentId(segmentId);
                reading.setSeq(readingInput.seq());
                reading.setAtTs(readingInput.at());
                reading.setTempCelsius(readingInput.tempCelsius());
                reading.setCreatedAt(now);
                readings.add(reading);
            }
            coldChainRepository.insertReadings(segmentId, readings);
        }

        List<ColdChainViolation> violations = gaps.stream()
                .map(gap -> {
                    ColdChainViolation violation = new ColdChainViolation();
                    violation.setLedgerId(ledger.getId());
                    violation.setVersion(version);
                    violation.setCode(gap.code());
                    violation.setSegmentSeq(gap.segmentSeq());
                    violation.setReadingSeq(gap.readingSeq());
                    violation.setAtTs(gap.at());
                    violation.setValue(gap.value());
                    violation.setMessage(gap.message());
                    violation.setCreatedAt(now);
                    return violation;
                })
                .toList();
        coldChainRepository.insertViolations(ledger.getId(), version, violations);
    }

    // ============================ 台账查询 ============================

    /** 查询冷链台账当前版本视图（缺口、越界点及冻结版本原样展示）。 */
    public ColdChainLedgerView getLedger(String batchNo) {
        Review review = reviewRepository.findByBatchNo(batchNo.trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
        }
        ColdChainLedger ledger = coldChainRepository.findByReviewId(review.getId());
        if (ledger == null) {
            throw new BusinessException(404, "COLDCHAIN_NOT_ENABLED",
                    "该子批未启用冷链运输台账: " + batchNo);
        }
        return toView(ledger);
    }

    // ============================ 视图装配 ============================

    /** 装配当前版本台账视图：冻结片段、读数、缺口/越界点与合格冻结版本。 */
    private ColdChainLedgerView toView(ColdChainLedger ledger) {
        List<ColdChainSegment> segments = coldChainRepository.listSegments(
                ledger.getId(), ledger.getVersion());
        List<ColdChainSegmentView> segmentViews = segments.stream()
                .map(segment -> new ColdChainSegmentView(
                        segment.getSeq(),
                        segment.getStartTs(),
                        segment.getEndTs(),
                        coldChainRepository.listReadings(segment.getId()).stream()
                                .map(reading -> new ColdChainReadingView(
                                        reading.getSeq(), reading.getAtTs(), reading.getTempCelsius()))
                                .toList()))
                .toList();
        List<ColdChainViolationView> violationViews = coldChainRepository
                .listViolations(ledger.getId(), ledger.getVersion()).stream()
                .map(violation -> new ColdChainViolationView(
                        violation.getCode(), violation.getSegmentSeq(), violation.getReadingSeq(),
                        violation.getAtTs(), violation.getValue(), violation.getMessage()))
                .toList();
        return new ColdChainLedgerView(
                ledger.getBatchNo(),
                ledger.getStatus().name(),
                ledger.getVersion(),
                ledger.getCertVersion(),
                ledger.isCertValid(),
                ledger.getTransportStart(),
                ledger.getTransportEnd(),
                ledger.getSubmittedAt(),
                ledger.getQualifiedAt(),
                segmentViews,
                violationViews);
    }

    // ============================ 幂等模板（失败不占键） ============================

    /**
     * requestId 幂等：同键同参重放首次冻结结果；同键异参 409；
     * 证据、温证与幂等结果同事务。业务失败统一抛 {@link BusinessException}（404/409/422），
     * 触发整事务回滚，占位记录一并回滚，因此失败不占用 requestId。
     * 并发同键落败方在占位插入处撞唯一键，等待首事务定稿后重放首次结果。
     */
    private ServiceResult idempotent(String requestId, String operation, String fingerprint,
                                     TxAction action) {
        while (true) {
            var existing = idempotencyRepository.find(requestId);
            if (existing != null) {
                return replayOrConflict(existing, fingerprint);
            }
            try {
                return txTemplate.execute(status -> {
                    idempotencyRepository.insertPlaceholder(requestId, operation, fingerprint,
                            Instant.now());
                    ServiceResult result = action.run();
                    idempotencyRepository.finalize(requestId, result.httpStatus(),
                            jsonSupport.write(result.body()));
                    return result;
                });
            } catch (DataAccessException ex) {
                // 并发同键：等待首事务定稿；若首事务失败回滚导致记录消失，则本请求自行重试。
                var winner = awaitFinalizedOrGone(requestId);
                if (winner == null) {
                    continue;
                }
                return replayOrConflict(winner, fingerprint);
            }
        }
    }

    @FunctionalInterface
    private interface TxAction {
        ServiceResult run();
    }

    private ServiceResult replayOrConflict(com.example.starter.domain.IdempotencyRecord record,
                                           String requestFingerprint) {
        if (!record.getFingerprint().equals(requestFingerprint)) {
            return ServiceResult.status(409, new ErrorResponse("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "同一 requestId 携带了不同业务参数（含全片段、读数、版本或精确时刻），请求被拒绝且业务状态不变"));
        }
        return ServiceResult.status(record.getHttpStatus(), jsonSupport.readObject(record.getResponseJson()));
    }

    /**
     * 等待并发首事务结果：定稿（http_status 非 0）则返回记录；记录缺失意味着首事务失败回滚、
     * 键已释放，返回 null 由调用方自行重试。
     */
    private com.example.starter.domain.IdempotencyRecord awaitFinalizedOrGone(String requestId) {
        for (int i = 0; i < 300; i++) {
            var record = idempotencyRepository.find(requestId);
            if (record == null) {
                return null;
            }
            if (record.getHttpStatus() != 0) {
                return record;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(500, "IDEMPOTENCY_WAIT_INTERRUPTED", "等待首次请求结果时被中断");
            }
        }
        throw new BusinessException(500, "IDEMPOTENCY_RECORD_NOT_FINALIZED",
                "并发首请求长时间未定稿: " + requestId);
    }
}
