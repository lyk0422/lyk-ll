package com.example.starter.ili;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ili.AlignmentService.AlignmentResult;
import com.example.starter.ili.AlignmentService.ReadingInput;
import com.example.starter.ili.dto.BatchResultResponse;
import com.example.starter.ili.dto.DefectInput;
import com.example.starter.ili.dto.PairView;
import com.example.starter.ili.dto.ReadingView;
import com.example.starter.ili.dto.ReviewRequest;
import com.example.starter.ili.dto.SealResponse;
import com.example.starter.ili.dto.SubmitBatchRequest;

/**
 * ILI 对齐批次编排：幂等提交、逐条复核与封板，全部在事务内完成。
 */
@Service
public class IliBatchService {

    private final IliBatchRepository batchRepo;
    private final DefectReadingRepository readingRepo;
    private final MatchPairRepository pairRepo;
    private final AlignmentService alignmentService;
    private final Clock clock;

    public IliBatchService(IliBatchRepository batchRepo, DefectReadingRepository readingRepo,
            MatchPairRepository pairRepo, AlignmentService alignmentService, Clock clock) {
        this.batchRepo = batchRepo;
        this.readingRepo = readingRepo;
        this.pairRepo = pairRepo;
        this.alignmentService = alignmentService;
        this.clock = clock;
    }

    /**
     * 提交对齐批次：同 requestId 同参数返回首次结果，同 requestId 异参数返回 409；
     * 任一读数写入失败则整个批次回滚。
     */
    @Transactional
    public BatchResultResponse submit(SubmitBatchRequest req) {
        validate(req);
        String fingerprint = fingerprint(req);

        var existing = batchRepo.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            BatchRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在但请求内容不一致，拒绝写入");
            }
            return buildResult(row.id());
        }

        long batchId;
        try {
            batchId = batchRepo.insert(req.requestId(), fingerprint, req.segmentId(),
                    req.runOldId(), req.runNewId(), req.runOldDate(), req.runNewDate(),
                    Instant.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已提交：按幂等重放处理。
            BatchRow row = batchRepo.findByRequestId(req.requestId())
                    .orElseThrow(() -> ApiException.conflict("requestId 并发提交冲突，请重试"));
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在但请求内容不一致，拒绝写入");
            }
            return buildResult(row.id());
        }

        List<ReadingInput> oldInputs = toInputs(req.oldDefects());
        List<ReadingInput> newInputs = toInputs(req.newDefects());
        List<Long> oldIds = insertReadings(batchId, RunSide.OLD, req.oldDefects(), oldInputs);
        List<Long> newIds = insertReadings(batchId, RunSide.NEW, req.newDefects(), newInputs);

        AlignmentResult result = alignmentService.align(oldInputs, newInputs,
                req.runOldDate(), req.runNewDate());
        for (var pair : result.pairs()) {
            pairRepo.insert(batchId, oldIds.get(pair.oldIndex()), newIds.get(pair.newIndex()),
                    pair.distanceM(), pair.growthRatePctPerYear(), pair.grade());
        }
        return buildResult(batchId);
    }

    /**
     * 逐条复核增长、严重缺陷；批次封板后拒绝且不改数据。
     */
    @Transactional
    public PairView review(long batchId, ReviewRequest req) {
        BatchRow batch = batchRepo.lockById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (batch.status() == BatchStatus.SEALED) {
            throw ApiException.conflict("批次已封板，拒绝复核修改");
        }
        PairRow pair = pairRepo.findByIdAndBatchId(req.pairId(), batchId)
                .orElseThrow(() -> ApiException.notFound("匹配对不存在: " + req.pairId()));
        if (pair.grade() == Grade.STABLE) {
            throw ApiException.badRequest("稳定缺陷无需复核");
        }
        if (pair.reviewStatus() != ReviewStatus.PENDING) {
            throw ApiException.conflict("该匹配对已复核，不允许重复复核");
        }

        ReviewStatus status = req.decision() == ReviewDecision.CONFIRM
                ? ReviewStatus.CONFIRMED : ReviewStatus.DOWNGRADED;
        Grade finalGrade = req.decision() == ReviewDecision.CONFIRM
                ? pair.grade() : downgrade(pair.grade());
        pairRepo.updateReview(pair.id(), status, finalGrade, req.reviewer(), req.comment(),
                Instant.now(clock));
        PairRow updated = pairRepo.findByIdAndBatchId(pair.id(), batchId).orElseThrow();
        return toPairView(updated, readingRefs(batchId));
    }

    /**
     * 封板：终态；重复封板返回首次封板结果的幂等成功。
     */
    @Transactional
    public SealResponse seal(long batchId) {
        BatchRow batch = batchRepo.lockById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (batch.status() == BatchStatus.SEALED) {
            return new SealResponse(batch.id(), batch.status().name(), batch.sealedAt());
        }
        Instant sealedAt = Instant.now(clock);
        batchRepo.markSealed(batchId, sealedAt);
        // 回读库内值，保证首次与后续幂等封板返回完全一致（库内时间戳精度为微秒）。
        BatchRow sealed = batchRepo.findById(batchId).orElseThrow();
        return new SealResponse(batchId, sealed.status().name(), sealed.sealedAt());
    }

    /**
     * 查询批次对齐结果。
     */
    @Transactional(readOnly = true)
    public BatchResultResponse getBatch(long batchId) {
        batchRepo.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        return buildResult(batchId);
    }

    private void validate(SubmitBatchRequest req) {
        if (!req.runNewDate().isAfter(req.runOldDate())) {
            throw ApiException.badRequest("本回合检测日期必须晚于上一回合检测日期");
        }
    }

    private List<ReadingInput> toInputs(List<DefectInput> defects) {
        return defects.stream()
                .map(d -> new ReadingInput(d.defectRef(),
                        BigDecimal.valueOf(d.absPositionM()), BigDecimal.valueOf(d.depthPct())))
                .toList();
    }

    private List<Long> insertReadings(long batchId, RunSide side, List<DefectInput> defects,
            List<ReadingInput> inputs) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < defects.size(); i++) {
            try {
                ids.add(readingRepo.insert(batchId, side, defects.get(i).defectRef(),
                        inputs.get(i).absPositionM(), inputs.get(i).depthPct(),
                        Instant.now(clock)));
            } catch (DuplicateKeyException e) {
                throw ApiException.badRequest(
                        "同批次同回合内缺陷编号重复: " + defects.get(i).defectRef());
            }
        }
        return ids;
    }

    private BatchResultResponse buildResult(long batchId) {
        BatchRow batch = batchRepo.findById(batchId).orElseThrow();
        List<ReadingRow> readings = readingRepo.findByBatchId(batchId);
        Map<Long, ReadingRow> byId = new HashMap<>();
        for (ReadingRow r : readings) {
            byId.put(r.id(), r);
        }
        List<PairRow> pairs = pairRepo.findByBatchId(batchId);
        Map<Long, String> refs = new HashMap<>();
        byId.forEach((id, r) -> refs.put(id, r.defectRef()));

        List<PairView> pairViews = pairs.stream().map(p -> toPairView(p, refs)).toList();
        var pairedOld = pairs.stream().map(PairRow::oldReadingId).collect(java.util.stream.Collectors.toSet());
        var pairedNew = pairs.stream().map(PairRow::newReadingId).collect(java.util.stream.Collectors.toSet());
        List<ReadingView> newFindings = new ArrayList<>();
        List<ReadingView> disappeared = new ArrayList<>();
        for (ReadingRow r : readings) {
            if (r.runSide() == RunSide.NEW && !pairedNew.contains(r.id())) {
                newFindings.add(toReadingView(r));
            } else if (r.runSide() == RunSide.OLD && !pairedOld.contains(r.id())) {
                disappeared.add(toReadingView(r));
            }
        }
        return new BatchResultResponse(batch.id(), batch.requestId(), batch.status().name(),
                batch.createdAt(), batch.sealedAt(), pairViews, newFindings, disappeared);
    }

    private Map<Long, String> readingRefs(long batchId) {
        Map<Long, String> refs = new HashMap<>();
        for (ReadingRow r : readingRepo.findByBatchId(batchId)) {
            refs.put(r.id(), r.defectRef());
        }
        return refs;
    }

    private PairView toPairView(PairRow p, Map<Long, String> refs) {
        return new PairView(p.id(), refs.get(p.oldReadingId()), refs.get(p.newReadingId()),
                p.distanceM(), p.growthRatePctPerYear(), p.grade().name(),
                p.reviewStatus().name(), p.finalGrade() == null ? null : p.finalGrade().name());
    }

    private ReadingView toReadingView(ReadingRow r) {
        return new ReadingView(r.defectRef(), r.absPositionM(), r.depthPct());
    }

    private Grade downgrade(Grade grade) {
        return switch (grade) {
            case SEVERE -> Grade.GROWING;
            case GROWING -> Grade.STABLE;
            case STABLE -> Grade.STABLE;
        };
    }

    /**
     * 规范化指纹：覆盖 requestId、管段、两回合标识与日期、全部缺陷读数的规范化摘要。
     */
    private String fingerprint(SubmitBatchRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.requestId()).append('\n')
                .append(req.segmentId()).append('\n')
                .append(req.runOldId()).append('\n')
                .append(req.runNewId()).append('\n')
                .append(req.runOldDate()).append('\n')
                .append(req.runNewDate()).append('\n');
        appendSide(sb, "OLD", req.oldDefects());
        appendSide(sb, "NEW", req.newDefects());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void appendSide(StringBuilder sb, String side, List<DefectInput> defects) {
        sb.append(side).append(':');
        defects.stream()
                .map(d -> d.defectRef() + "=" + normalize(d.absPositionM()) + "/" + normalize(d.depthPct()))
                .sorted(Comparator.naturalOrder())
                .forEach(s -> sb.append(s).append(';'));
        sb.append('\n');
    }

    private String normalize(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
