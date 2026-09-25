package com.example.starter.ili;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缺陷对齐批次服务：双回合导入与对齐（requestId 幂等、整体事务）、
 * 分级复核与封板（封板为终态，复核与封板通过批次行锁串行化）。
 */
@Service
public class AlignmentService {

    /** 批次状态：已对齐待复核。 */
    public static final String STATUS_ALIGNED = "ALIGNED";
    /** 批次状态：已封板（终态）。 */
    public static final String STATUS_SEALED = "SEALED";

    private static final String MATCH_MATCHED = "MATCHED";
    private static final String MATCH_NEW_FINDING = "NEW_FINDING";
    private static final String MATCH_DISAPPEARED = "DISAPPEARED";

    private static final String REVIEW_NOT_REQUIRED = "NOT_REQUIRED";
    private static final String REVIEW_PENDING = "PENDING";
    private static final String REVIEW_CONFIRMED = "CONFIRMED";
    private static final String REVIEW_DOWNGRADED = "DOWNGRADED";

    private final AlignmentRepository alignmentRepository;
    private final SegmentRepository segmentRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AlignmentService(AlignmentRepository alignmentRepository, SegmentRepository segmentRepository,
            org.springframework.transaction.PlatformTransactionManager transactionManager, Clock clock) {
        this.alignmentRepository = alignmentRepository;
        this.segmentRepository = segmentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 导入与对齐结果，created 标识是否本次新建（false 表示幂等重放）。 */
    public record ImportResult(Dtos.AlignmentBatchResponse response, boolean created) {
    }

    /**
     * 导入两个检测回合的缺陷清单并完成对齐分级。
     * 同 requestId 同参数返回首次结果；同 requestId 异参数返回 409；导入整体成功或整体回滚。
     */
    public ImportResult importAndAlign(Dtos.ImportAlignmentRequest request) {
        validateRequest(request);
        String fingerprint = Fingerprint.of(request);
        var existing = alignmentRepository.findBatchByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        try {
            return transactionTemplate.execute(status -> doImport(request, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 抢先提交：以数据库中已落库的首次结果为准
            var winner = alignmentRepository.findBatchByRequestId(request.requestId());
            if (winner.isPresent()) {
                return replayOrConflict(winner.get(), fingerprint);
            }
            throw e;
        }
    }

    /** 事务内执行：管段/回合解析、对齐、分级与全部落库，任一失败整体回滚。 */
    private ImportResult doImport(Dtos.ImportAlignmentRequest request, String fingerprint) {
        SegmentRepository.SegmentRow segment = segmentRepository.findByCode(request.segmentCode())
                .orElseThrow(() -> ApiException.notFound("管段不存在: " + request.segmentCode()));
        AlignmentRepository.RunRow run1 = resolveRun(segment.id(), request.run1());
        AlignmentRepository.RunRow run2 = resolveRun(segment.id(), request.run2());

        MatchingEngine.MatchResult match = MatchingEngine.match(
                toInputs(request.run1().defects()), toInputs(request.run2().defects()));

        long batchId = alignmentRepository.insertBatch(request.requestId(), fingerprint, segment.id(),
                run1.id(), run2.id(), STATUS_ALIGNED, match.pairs().size(), match.newFindings().size(),
                match.disappeared().size(), Instant.now(clock));

        // 旧回合读数：匹配对中的为 MATCHED，其余 DISAPPEARED；新回合读数：匹配对中的为 MATCHED，其余 NEW_FINDING
        Set<String> matchedOldRefs = new HashSet<>();
        Set<String> matchedNewRefs = new HashSet<>();
        for (MatchingEngine.MatchedPair pair : match.pairs()) {
            matchedOldRefs.add(pair.oldDefect().defectRef());
            matchedNewRefs.add(pair.newDefect().defectRef());
        }
        Map<String, Long> oldReadingIds = insertReadings(batchId, run1.id(), request.run1().defects(),
                matchedOldRefs, MATCH_DISAPPEARED);
        Map<String, Long> newReadingIds = insertReadings(batchId, run2.id(), request.run2().defects(),
                matchedNewRefs, MATCH_NEW_FINDING);

        for (MatchingEngine.MatchedPair pair : match.pairs()) {
            BigDecimal rate = GradePolicy.annualGrowthRate(pair.oldDefect().depthPct(),
                    pair.newDefect().depthPct(), run1.runDate(), run2.runDate());
            GradePolicy.Grade grade = GradePolicy.grade(rate);
            String reviewStatus = grade == GradePolicy.Grade.STABLE ? REVIEW_NOT_REQUIRED : REVIEW_PENDING;
            alignmentRepository.insertPair(batchId, oldReadingIds.get(pair.oldDefect().defectRef()),
                    newReadingIds.get(pair.newDefect().defectRef()), pair.mileageDiffM(), rate,
                    grade.name(), reviewStatus);
        }
        AlignmentRepository.BatchRow batch = alignmentRepository.findBatchById(batchId).orElseThrow();
        return new ImportResult(assembleResponse(batch), true);
    }

    private Map<String, Long> insertReadings(long batchId, long runId, List<Dtos.DefectPayload> defects,
            Set<String> matchedRefs, String unmatchedStatus) {
        Map<String, Long> ids = new HashMap<>();
        for (Dtos.DefectPayload defect : defects) {
            String status = matchedRefs.contains(defect.defectRef()) ? MATCH_MATCHED : unmatchedStatus;
            long id = alignmentRepository.insertReading(batchId, runId, defect.defectRef(),
                    defect.absMileageM(), defect.depthPct(), status);
            ids.put(defect.defectRef(), id);
        }
        return ids;
    }

    /** 回合登记：已存在则校验检测日期一致，否则新建（并发冲突时以已落库记录为准）。 */
    private AlignmentRepository.RunRow resolveRun(long segmentId, Dtos.RunPayload payload) {
        var existing = alignmentRepository.findRun(segmentId, payload.runCode());
        if (existing.isPresent()) {
            return checkRunDate(existing.get(), payload);
        }
        try {
            long runId = alignmentRepository.insertRun(segmentId, payload.runCode(), payload.runDate(),
                    Instant.now(clock));
            return alignmentRepository.findRunById(runId).orElseThrow();
        } catch (DuplicateKeyException e) {
            var winner = alignmentRepository.findRun(segmentId, payload.runCode()).orElseThrow();
            return checkRunDate(winner, payload);
        }
    }

    private AlignmentRepository.RunRow checkRunDate(AlignmentRepository.RunRow run, Dtos.RunPayload payload) {
        if (!run.runDate().equals(payload.runDate())) {
            throw ApiException.unprocessable("检测回合 " + payload.runCode() + " 已登记，检测日期不一致: "
                    + run.runDate() + " vs " + payload.runDate());
        }
        return run;
    }

    /** 幂等重放或异参冲突：指纹一致返回首次结果，否则 409 且不写入任何数据。 */
    private ImportResult replayOrConflict(AlignmentRepository.BatchRow batch, String fingerprint) {
        if (!batch.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在但请求参数不一致，拒绝写入: " + batch.requestId());
        }
        return new ImportResult(assembleResponse(batch), false);
    }

    /**
     * 查询批次详情。
     */
    @Transactional(readOnly = true)
    public Dtos.AlignmentBatchResponse getBatch(long batchId) {
        AlignmentRepository.BatchRow batch = alignmentRepository.findBatchById(batchId)
                .orElseThrow(() -> ApiException.notFound("对齐批次不存在: " + batchId));
        return assembleResponse(batch);
    }

    /**
     * 对增长/严重匹配对提交复核结论（确认或降级）。封板后拒绝且不改数据。
     */
    @Transactional
    public Dtos.ReviewResponse review(long batchId, Dtos.ReviewRequest request) {
        AlignmentRepository.BatchRow batch = alignmentRepository.lockBatchById(batchId)
                .orElseThrow(() -> ApiException.notFound("对齐批次不存在: " + batchId));
        if (STATUS_SEALED.equals(batch.status())) {
            throw ApiException.conflict("批次已封板，拒绝迟到复核: " + batchId);
        }
        AlignmentRepository.PairRow pair = alignmentRepository.findPair(request.pairId())
                .orElseThrow(() -> ApiException.notFound("匹配对不存在: " + request.pairId()));
        if (pair.batchId() != batchId) {
            throw ApiException.unprocessable("匹配对 " + request.pairId() + " 不属于批次 " + batchId);
        }
        if (REVIEW_NOT_REQUIRED.equals(pair.reviewStatus())) {
            throw ApiException.unprocessable("稳定缺陷无需复核: " + request.pairId());
        }
        if (!REVIEW_PENDING.equals(pair.reviewStatus())) {
            throw ApiException.conflict("匹配对已完成复核，不允许重复复核: " + request.pairId());
        }
        String reviewStatus;
        String finalGrade;
        if (request.decision() == Dtos.ReviewDecision.CONFIRM) {
            reviewStatus = REVIEW_CONFIRMED;
            finalGrade = pair.grade();
        } else {
            reviewStatus = REVIEW_DOWNGRADED;
            finalGrade = "SEVERE".equals(pair.grade()) ? "GROWING" : "STABLE";
        }
        alignmentRepository.updatePairReview(pair.id(), reviewStatus, finalGrade, request.reviewer(),
                Instant.now(clock));
        return new Dtos.ReviewResponse(pair.id(), batchId, reviewStatus, finalGrade);
    }

    /**
     * 封板：要求增长/严重缺陷均已复核；封板为终态，重复封板返回幂等成功。
     */
    @Transactional
    public Dtos.SealResponse seal(long batchId) {
        AlignmentRepository.BatchRow batch = alignmentRepository.lockBatchById(batchId)
                .orElseThrow(() -> ApiException.notFound("对齐批次不存在: " + batchId));
        if (STATUS_SEALED.equals(batch.status())) {
            // 封板为终态，重复封板返回幂等成功
            return new Dtos.SealResponse(batch.id(), STATUS_SEALED, batch.sealedAt());
        }
        int pending = alignmentRepository.countPendingReviews(batchId);
        if (pending > 0) {
            throw ApiException.conflict("存在 " + pending + " 条增长/严重缺陷未复核，不能封板: " + batchId);
        }
        Instant sealedAt = Instant.now(clock);
        alignmentRepository.sealBatch(batchId, sealedAt);
        // 重新读取落库后的批次，保证首次封板与后续幂等重放返回完全一致的封板时间
        AlignmentRepository.BatchRow sealed = alignmentRepository.findBatchById(batchId).orElseThrow();
        return new Dtos.SealResponse(sealed.id(), STATUS_SEALED, sealed.sealedAt());
    }

    /** 由数据库状态组装批次响应，保证幂等重放与首次结果一致。 */
    private Dtos.AlignmentBatchResponse assembleResponse(AlignmentRepository.BatchRow batch) {
        SegmentRepository.SegmentRow segment = segmentRepository.findById(batch.segmentId()).orElseThrow();
        AlignmentRepository.RunRow run1 = alignmentRepository.findRunById(batch.run1Id()).orElseThrow();
        AlignmentRepository.RunRow run2 = alignmentRepository.findRunById(batch.run2Id()).orElseThrow();
        List<AlignmentRepository.ReadingRow> readings = alignmentRepository.findReadings(batch.id());
        Map<Long, AlignmentRepository.ReadingRow> readingById = new HashMap<>();
        for (AlignmentRepository.ReadingRow reading : readings) {
            readingById.put(reading.id(), reading);
        }
        List<Dtos.PairView> pairs = new ArrayList<>();
        for (AlignmentRepository.PairRow pair : alignmentRepository.findPairs(batch.id())) {
            pairs.add(new Dtos.PairView(pair.id(),
                    readingById.get(pair.oldReadingId()).defectRef(),
                    readingById.get(pair.newReadingId()).defectRef(),
                    pair.mileageDiffM(), pair.growthRatePctYear(), pair.grade(), pair.reviewStatus(),
                    pair.finalGrade(), pair.reviewer()));
        }
        List<Dtos.DefectView> newFindings = new ArrayList<>();
        List<Dtos.DefectView> disappeared = new ArrayList<>();
        for (AlignmentRepository.ReadingRow reading : readings) {
            if (MATCH_NEW_FINDING.equals(reading.matchStatus())) {
                newFindings.add(toDefectView(reading));
            } else if (MATCH_DISAPPEARED.equals(reading.matchStatus())) {
                disappeared.add(toDefectView(reading));
            }
        }
        return new Dtos.AlignmentBatchResponse(batch.id(), batch.requestId(), segment.segmentCode(),
                run1.runCode(), run2.runCode(), batch.status(), List.copyOf(pairs),
                List.copyOf(newFindings), List.copyOf(disappeared), batch.createdAt(), batch.sealedAt());
    }

    private static Dtos.DefectView toDefectView(AlignmentRepository.ReadingRow reading) {
        return new Dtos.DefectView(reading.defectRef(), reading.absMileageM(), reading.depthPct());
    }

    private static List<MatchingEngine.DefectInput> toInputs(List<Dtos.DefectPayload> defects) {
        return defects.stream()
                .map(d -> new MatchingEngine.DefectInput(d.defectRef(), d.absMileageM(), d.depthPct()))
                .toList();
    }

    private static void validateRequest(Dtos.ImportAlignmentRequest request) {
        if (request.run1().runCode().equals(request.run2().runCode())) {
            throw ApiException.unprocessable("两个检测回合标识不能相同: " + request.run1().runCode());
        }
        if (!request.run1().runDate().isBefore(request.run2().runDate())) {
            throw ApiException.unprocessable("run1 检测日期必须早于 run2 检测日期");
        }
        checkDuplicateRefs(request.run1());
        checkDuplicateRefs(request.run2());
    }

    private static void checkDuplicateRefs(Dtos.RunPayload run) {
        Set<String> refs = new HashSet<>();
        for (Dtos.DefectPayload defect : run.defects()) {
            if (!refs.add(defect.defectRef())) {
                throw ApiException.unprocessable(
                        "回合 " + run.runCode() + " 内缺陷编号重复: " + defect.defectRef());
            }
        }
    }
}
