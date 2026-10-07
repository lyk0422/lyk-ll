package com.example.starter.service;

import com.example.starter.coldchain.ColdChainEvaluator;
import com.example.starter.domain.ColdChainLedger;
import com.example.starter.domain.ColdChainReading;
import com.example.starter.domain.ColdChainSegment;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewEvent;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.ColdChainCorrectRequest;
import com.example.starter.dto.ColdChainReadingView;
import com.example.starter.dto.ColdChainReadingsSubmitRequest;
import com.example.starter.dto.ColdChainSegmentInput;
import com.example.starter.dto.ColdChainSegmentReadingsInput;
import com.example.starter.dto.ColdChainSegmentRegisterRequest;
import com.example.starter.dto.ColdChainSegmentView;
import com.example.starter.dto.ColdChainView;
import com.example.starter.dto.ColdChainViolationView;
import com.example.starter.dto.ErrorResponse;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 受控包装子批冷链片段核验服务：运输片段登记、温度读数提交（重核）、补录/更正与台账查询。
 *
 * <p>登记 1 至 5 个顺序运输片段即创建台账（版本 1）并冻结完整运输起止；
 * 完整证据合格即冻结读数版本并签发温证；未签发前补录/更正递增版本并使旧温证资格失效，
 * 必须重核。签发门禁在 {@link ReviewService} 原签发入口同事务检查当前温证。
 *
 * <p>并发统一由行锁序列化，加锁顺序保持 batch_review → cold_chain_ledger
 * （全局为 customs_declaration → subbatch_binding → batch_review → cold_chain_ledger）。
 * 新写操作以 requestId 幂等：同参重放、异参 409、失败（含证据不合格 422）不占键；
 * 证据、温证与幂等结果同事务提交。
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

    // ============================ 片段登记（创建台账，版本 1） ============================

    /**
     * 登记运输片段（requestId 幂等）：创建台账版本 1 并冻结完整运输起止。
     * 已登记台账的子批重复登记 409；终态审查流程 422。
     */
    public ServiceResult registerSegments(ColdChainSegmentRegisterRequest request) {
        return idempotent(request.requestId(), Fingerprints.COLDCHAIN_REGISTER,
                Fingerprints.coldChainRegister(request), () -> doRegisterSegments(request));
    }

    private ServiceResult doRegisterSegments(ColdChainSegmentRegisterRequest request) {
        Instant now = request.at();
        Review review = reviewRepository.lockByBatchNo(request.batchNo().trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + request.batchNo());
        }
        requireNotFinal(review);
        List<ColdChainSegment> segments = buildSegments(request.segments());
        if (coldChainRepository.findLedgerByReviewId(review.getId()) != null) {
            throw new BusinessException(409, "COLD_CHAIN_ALREADY_REGISTERED",
                    "该子批已登记冷链运输台账，补录/更正请走更正入口: " + review.getBatchNo());
        }

        ColdChainLedger ledger = new ColdChainLedger();
        ledger.setReviewId(review.getId());
        ledger.setBatchNo(review.getBatchNo());
        ledger.setTransportStartUtc(segments.get(0).getStartUtc());
        ledger.setTransportEndUtc(segments.get(segments.size() - 1).getEndUtc());
        ledger.setVersion(1);
        ledger.setQualifiedVersion(null);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        Long ledgerId = coldChainRepository.insertLedger(ledger);
        if (ledgerId == null) {
            // 并发登记在 uk_coldchain_review 上落败（review 行锁序列化后的兜底）。
            throw new BusinessException(409, "COLD_CHAIN_ALREADY_REGISTERED",
                    "该子批已登记冷链运输台账: " + review.getBatchNo());
        }
        ledger.setId(ledgerId);
        segments.forEach(segment -> {
            segment.setLedgerId(ledgerId);
            segment.setLedgerVersion(1);
            segment.setCreatedAt(now);
        });
        coldChainRepository.insertSegments(segments);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "COLD_CHAIN_REGISTERED",
                jsonSupport.write(Map.of(
                        "version", 1,
                        "segmentCount", segments.size(),
                        "transportStartUtc", ledger.getTransportStartUtc().toString(),
                        "transportEndUtc", ledger.getTransportEndUtc().toString())), now));
        return ServiceResult.ok(toView(ledger, segments, Map.of()));
    }

    // ============================ 读数提交（重核，合格即冻结） ============================

    /**
     * 提交温度读数并重核（requestId 幂等，失败不占键）。
     * 完整证据合格即冻结当前版本读数并签发温证；不合格证据仍落库供查询展示缺口与越界点，
     * 但温证不签发、requestId 不占用，可修正后重新提交。
     */
    public ServiceResult submitReadings(ColdChainReadingsSubmitRequest request) {
        return idempotent(request.requestId(), Fingerprints.COLDCHAIN_READINGS,
                Fingerprints.coldChainReadings(request), () -> doSubmitReadings(request));
    }

    private ServiceResult doSubmitReadings(ColdChainReadingsSubmitRequest request) {
        Instant now = request.at();
        Review review = reviewRepository.lockByBatchNo(request.batchNo().trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + request.batchNo());
        }
        requireNotFinal(review);
        ColdChainLedger ledger = coldChainRepository.lockLedgerByReviewId(review.getId());
        if (ledger == null) {
            throw new BusinessException(404, "COLD_CHAIN_NOT_REGISTERED",
                    "该子批未登记冷链运输台账，非受控子批: " + review.getBatchNo());
        }
        if (ledger.getVersion() != request.expectedVersion()) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "冷链台账版本已变化，期望 " + request.expectedVersion()
                            + "，当前 " + ledger.getVersion());
        }
        List<ColdChainSegment> segments = coldChainRepository.listSegments(
                ledger.getId(), ledger.getVersion());
        Map<Integer, List<ColdChainReading>> submitted =
                buildReadings(ledger, segments, request, now);

        if (ledger.isCertificateValid()) {
            // 当前版本证据已冻结：同参视为重放成功；异参拒绝，更正须先走更正入口递增版本。
            Map<Integer, List<ColdChainReading>> stored =
                    groupReadings(coldChainRepository.listReadings(ledger.getId(), ledger.getVersion()));
            if (sameReadings(stored, submitted)) {
                return ServiceResult.ok(toView(ledger, segments, stored));
            }
            throw new BusinessException(422, "COLD_CHAIN_EVIDENCE_FROZEN",
                    "当前版本冷链证据已冻结合格，补录/更正须先走更正入口递增版本");
        }

        ColdChainEvaluator.Evaluation evaluation =
                ColdChainEvaluator.evaluate(segments, submitted, now);
        // 证据落库：替换当前版本未合格证据（已冻结版本不会走到这里），合格与不合格都保留，
        // 供查询展示缺口与越界点；缺样本只记缺口，不当作零度。
        coldChainRepository.deleteReadings(ledger.getId(), ledger.getVersion());
        coldChainRepository.insertReadings(
                submitted.values().stream().flatMap(List::stream).toList());

        if (evaluation.qualified()) {
            ledger.setQualifiedVersion(ledger.getVersion());
            ledger.setUpdatedAt(now);
            coldChainRepository.updateLedger(ledger);
            reviewRepository.insertEvent(new ReviewEvent(review.getId(), "COLD_CHAIN_CERT_QUALIFIED",
                    jsonSupport.write(Map.of(
                            "version", ledger.getVersion(),
                            "readingCount", submitted.values().stream().mapToInt(List::size).sum())),
                    now));
            return ServiceResult.ok(toView(ledger, segments, submitted));
        }

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "COLD_CHAIN_EVIDENCE_FAILED",
                jsonSupport.write(Map.of(
                        "version", ledger.getVersion(),
                        "gaps", evaluation.gaps().size(),
                        "violations", evaluation.violations().size())), now));
        if (!evaluation.violations().isEmpty()) {
            return ServiceResult.status(422, new ErrorResponse("COLD_CHAIN_READING_OUT_OF_RANGE",
                    "冷链读数存在越界点，不得通过: " + describeViolations(evaluation.violations())));
        }
        return ServiceResult.status(422, new ErrorResponse("COLD_CHAIN_EVIDENCE_INCOMPLETE",
                "冷链证据存在缺口，不得通过: " + String.join("；", evaluation.gaps())));
    }

    // ============================ 补录/更正（版本 +1，旧温证失效） ============================

    /**
     * 补录/更正运输片段（requestId 幂等，失败不占键）。
     * 未签发前成功即版本加 1、旧温证资格失效，须重新提交读数重核；
     * 已签发则仅登记风险事件，不回改旧证书及核销数量。
     */
    public ServiceResult correct(ColdChainCorrectRequest request) {
        return idempotent(request.requestId(), Fingerprints.COLDCHAIN_CORRECT,
                Fingerprints.coldChainCorrect(request), () -> doCorrect(request));
    }

    private ServiceResult doCorrect(ColdChainCorrectRequest request) {
        Instant now = request.at();
        Review review = reviewRepository.lockByBatchNo(request.batchNo().trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + request.batchNo());
        }
        ColdChainLedger ledger = coldChainRepository.lockLedgerByReviewId(review.getId());
        if (ledger == null) {
            throw new BusinessException(404, "COLD_CHAIN_NOT_REGISTERED",
                    "该子批未登记冷链运输台账，非受控子批: " + review.getBatchNo());
        }
        if (review.getStatus() == ReviewStatus.ISSUED) {
            // 签发先成：仅登记风险事件，不回改旧温证、版本及核销数量。
            reviewRepository.insertEvent(new ReviewEvent(review.getId(),
                    "COLD_CHAIN_CORRECTION_AFTER_ISSUE",
                    jsonSupport.write(Map.of(
                            "submittedVersion", request.expectedVersion(),
                            "currentVersion", ledger.getVersion(),
                            "risk", "子批已签发，补录/更正仅登记风险，旧温证与核销数量保持不变")), now));
            return ServiceResult.ok(toView(ledger));
        }
        requireNotFinal(review);
        if (ledger.getVersion() != request.expectedVersion()) {
            throw new BusinessException(409, "VERSION_CONFLICT",
                    "冷链台账版本已变化，期望 " + request.expectedVersion()
                            + "，当前 " + ledger.getVersion());
        }
        List<ColdChainSegment> segments = buildSegments(request.segments());

        int oldVersion = ledger.getVersion();
        ledger.setVersion(oldVersion + 1);
        ledger.setTransportStartUtc(segments.get(0).getStartUtc());
        ledger.setTransportEndUtc(segments.get(segments.size() - 1).getEndUtc());
        ledger.setUpdatedAt(now);
        // qualifiedVersion 保持旧值：与新版本不等即旧温证资格失效，必须重核。
        coldChainRepository.updateLedger(ledger);
        segments.forEach(segment -> {
            segment.setLedgerId(ledger.getId());
            segment.setLedgerVersion(ledger.getVersion());
            segment.setCreatedAt(now);
        });
        coldChainRepository.insertSegments(segments);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "COLD_CHAIN_CORRECTED",
                jsonSupport.write(Map.of(
                        "oldVersion", oldVersion,
                        "newVersion", ledger.getVersion(),
                        "segmentCount", segments.size())), now));
        return ServiceResult.ok(toView(ledger, segments, Map.of()));
    }

    // ============================ 台账查询（展示缺口、越界点及冻结版本） ============================

    /** 查询冷链台账视图：当前版本证据、缺口、越界点及温证冻结版本；缺样本不当作零度。 */
    public ColdChainView getView(String batchNo) {
        Review review = reviewRepository.findByBatchNo(batchNo.trim());
        if (review == null) {
            throw new BusinessException(404, "REVIEW_NOT_FOUND", "批号不存在: " + batchNo);
        }
        ColdChainLedger ledger = coldChainRepository.findLedgerByReviewId(review.getId());
        if (ledger == null) {
            throw new BusinessException(404, "COLD_CHAIN_NOT_REGISTERED",
                    "该子批未登记冷链运输台账，非受控子批: " + review.getBatchNo());
        }
        return toView(ledger);
    }

    // ============================ 幂等模板（失败不占键） ============================

    /**
     * requestId 幂等：同键同参重放首次冻结结果；同键异参 409。
     * 仅成功结果（httpStatus &lt; 400）在业务事务内写入幂等记录；
     * 业务失败抛 {@link BusinessException} 整事务回滚、证据不合格 422 不写幂等记录，
     * 因此失败均不占用 requestId。并发同键在占位插入处撞主键，等待首事务定稿后重放。
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
                    ServiceResult result = action.run();
                    if (result.httpStatus() < 400) {
                        idempotencyRepository.insertPlaceholder(requestId, operation, fingerprint,
                                java.time.Instant.now());
                        idempotencyRepository.finalize(requestId, result.httpStatus(),
                                jsonSupport.write(result.body()));
                    }
                    return result;
                });
            } catch (DataAccessException ex) {
                // 并发同键：等待首事务定稿；首事务失败回滚导致记录消失则本请求自行重试。
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
                    "同一 requestId 携带了不同业务参数（含片段、读数、版本或精确时刻），请求被拒绝且业务状态不变"));
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

    // ============================ 内部辅助 ============================

    /** 终态（已签发/已驳回/已撤回）不允许登记、提交读数或更正。 */
    private static void requireNotFinal(Review review) {
        if (review.getStatus() == ReviewStatus.ISSUED
                || review.getStatus() == ReviewStatus.REJECTED
                || review.getStatus() == ReviewStatus.WITHDRAWN) {
            throw new BusinessException(422, "COLD_CHAIN_REVIEW_FINAL",
                    "审查流程已终态（" + review.getStatus() + "），不允许变更冷链证据");
        }
    }

    /** 校验片段结构并转换为领域对象（未关联台账）。 */
    private static List<ColdChainSegment> buildSegments(List<ColdChainSegmentInput> inputs) {
        List<String> problems = ColdChainEvaluator.validateSegmentStructure(inputs);
        if (!problems.isEmpty()) {
            throw new BusinessException(422, "INVALID_SEGMENT_STRUCTURE",
                    "运输片段结构不合法: " + String.join("；", problems));
        }
        List<ColdChainSegment> segments = new ArrayList<>();
        for (ColdChainSegmentInput input : inputs) {
            ColdChainSegment segment = new ColdChainSegment();
            segment.setSegmentSeq(input.seq());
            segment.setStartUtc(input.startUtc());
            segment.setEndUtc(input.endUtc());
            segments.add(segment);
        }
        return segments;
    }

    /** 将提交读数转换为按片段分组的领域对象；未知片段、缺序号/时刻/温度整笔拒绝。 */
    private static Map<Integer, List<ColdChainReading>> buildReadings(
            ColdChainLedger ledger, List<ColdChainSegment> segments,
            ColdChainReadingsSubmitRequest request, Instant now) {
        Set<Integer> knownSeqs = segments.stream()
                .map(ColdChainSegment::getSegmentSeq).collect(Collectors.toSet());
        List<Integer> unknown = request.segments().stream()
                .map(ColdChainSegmentReadingsInput::segmentSeq)
                .filter(seq -> seq == null || !knownSeqs.contains(seq))
                .toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(422, "COLD_CHAIN_UNKNOWN_SEGMENT",
                    "提交了未登记的片段序号: " + unknown);
        }
        Map<Integer, List<ColdChainReading>> bySegment = new LinkedHashMap<>();
        for (ColdChainSegmentReadingsInput block : request.segments()) {
            if (bySegment.containsKey(block.segmentSeq())) {
                throw new BusinessException(422, "COLD_CHAIN_DUPLICATE_SEGMENT_BLOCK",
                        "片段 " + block.segmentSeq() + " 的读数块重复提交");
            }
            List<ColdChainReading> readings = new ArrayList<>();
            for (var input : block.readings()) {
                if (input.seq() == null || input.at() == null || input.temperature() == null) {
                    // 防御：HTTP 层 @NotNull 已拦截，服务层直调同样不得放行缺序号读数。
                    throw new BusinessException(422, "COLD_CHAIN_MISSING_READING_FIELD",
                            "读数缺序号、精确时刻或温度，不得通过（片段 " + block.segmentSeq() + "）");
                }
                ColdChainReading reading = new ColdChainReading();
                reading.setLedgerId(ledger.getId());
                reading.setLedgerVersion(ledger.getVersion());
                reading.setSegmentSeq(block.segmentSeq());
                reading.setReadingSeq(input.seq());
                reading.setReadAt(input.at());
                reading.setTemperature(input.temperature());
                reading.setCreatedAt(now);
                readings.add(reading);
            }
            bySegment.put(block.segmentSeq(), readings);
        }
        return bySegment;
    }

    private static Map<Integer, List<ColdChainReading>> groupReadings(
            List<ColdChainReading> readings) {
        return readings.stream().collect(Collectors.groupingBy(
                ColdChainReading::getSegmentSeq, LinkedHashMap::new, Collectors.toList()));
    }

    /** 已冻结证据比对：片段集合、片内序号、精确时刻与温度逐项一致（温度按数值比较）。 */
    private static boolean sameReadings(Map<Integer, List<ColdChainReading>> stored,
                                        Map<Integer, List<ColdChainReading>> submitted) {
        if (!stored.keySet().equals(submitted.keySet())) {
            return false;
        }
        for (Map.Entry<Integer, List<ColdChainReading>> entry : stored.entrySet()) {
            List<ColdChainReading> expected = sorted(entry.getValue());
            List<ColdChainReading> actual = sorted(submitted.get(entry.getKey()));
            if (expected.size() != actual.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                ColdChainReading x = expected.get(i);
                ColdChainReading y = actual.get(i);
                if (x.getReadingSeq() != y.getReadingSeq()
                        || !x.getReadAt().equals(y.getReadAt())
                        || x.getTemperature().compareTo(y.getTemperature()) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<ColdChainReading> sorted(List<ColdChainReading> readings) {
        return readings.stream()
                .sorted(Comparator.comparingInt(ColdChainReading::getReadingSeq))
                .toList();
    }

    private static String describeViolations(List<ColdChainEvaluator.Violation> violations) {
        return violations.stream()
                .map(v -> "片段 " + v.segmentSeq() + " 序号 " + v.readingSeq()
                        + "（" + v.at() + "，" + v.temperature().toPlainString() + " 度）: " + v.reason())
                .collect(Collectors.joining("；"));
    }

    private ColdChainView toView(ColdChainLedger ledger) {
        List<ColdChainSegment> segments = coldChainRepository.listSegments(
                ledger.getId(), ledger.getVersion());
        Map<Integer, List<ColdChainReading>> bySegment =
                groupReadings(coldChainRepository.listReadings(ledger.getId(), ledger.getVersion()));
        return toView(ledger, segments, bySegment);
    }

    private ColdChainView toView(ColdChainLedger ledger, List<ColdChainSegment> segments,
                                 Map<Integer, List<ColdChainReading>> bySegment) {
        // 查询展示：缺口与越界点按当前版本证据复算；缺样本只列缺口，不补零度。
        ColdChainEvaluator.Evaluation evaluation =
                ColdChainEvaluator.evaluate(segments, bySegment, null);
        List<ColdChainSegmentView> segmentViews = segments.stream()
                .map(segment -> new ColdChainSegmentView(
                        segment.getSegmentSeq(),
                        segment.getStartUtc(),
                        segment.getEndUtc(),
                        sorted(bySegment.getOrDefault(segment.getSegmentSeq(), List.of())).stream()
                                .map(reading -> new ColdChainReadingView(reading.getReadingSeq(),
                                        reading.getReadAt(), reading.getTemperature()))
                                .toList()))
                .toList();
        List<ColdChainViolationView> violationViews = evaluation.violations().stream()
                .map(v -> new ColdChainViolationView(v.segmentSeq(), v.readingSeq(), v.at(),
                        v.temperature(), v.reason()))
                .toList();
        return new ColdChainView(
                ledger.getBatchNo(),
                ledger.getVersion(),
                ledger.getQualifiedVersion(),
                ledger.isCertificateValid(),
                ledger.getTransportStartUtc(),
                ledger.getTransportEndUtc(),
                segmentViews,
                evaluation.gaps(),
                violationViews);
    }
}
