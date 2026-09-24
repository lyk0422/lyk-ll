package com.example.starter.ili;

import com.example.starter.ili.api.ApiException;
import com.example.starter.ili.api.BatchView;
import com.example.starter.ili.api.ImportBatchRequest;
import com.example.starter.ili.api.MatchView;
import com.example.starter.ili.api.ReadingPayload;
import com.example.starter.ili.api.ReviewRequest;
import com.example.starter.ili.model.AlignmentOutcome;
import com.example.starter.ili.model.BatchStatus;
import com.example.starter.ili.model.Grade;
import com.example.starter.ili.model.MatchResult;
import com.example.starter.ili.model.ReadingInput;
import com.example.starter.ili.model.ReviewStatus;
import com.example.starter.ili.model.RunSide;
import com.example.starter.ili.persistence.BatchRepository;
import com.example.starter.ili.persistence.BatchRow;
import com.example.starter.ili.persistence.MatchRepository;
import com.example.starter.ili.persistence.MatchRow;
import com.example.starter.ili.persistence.ReadingRepository;
import com.example.starter.ili.persistence.ReadingRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对齐批次应用服务：双回合导入（幂等、整体事务）、复核与封板。
 */
@Service
public class AlignmentService {

    private final BatchRepository batchRepository;
    private final ReadingRepository readingRepository;
    private final MatchRepository matchRepository;
    private final MatchingEngine matchingEngine;
    private final Clock clock;

    public AlignmentService(BatchRepository batchRepository, ReadingRepository readingRepository,
                            MatchRepository matchRepository, MatchingEngine matchingEngine, Clock clock) {
        this.batchRepository = batchRepository;
        this.readingRepository = readingRepository;
        this.matchRepository = matchRepository;
        this.matchingEngine = matchingEngine;
        this.clock = clock;
    }

    /**
     * 导入双回合缺陷清单并完成对齐分级。整体事务：任一步失败全部回滚。
     * 按 requestId 幂等：同键同参返回首次结果，同键异参返回 409。
     */
    @Transactional
    public BatchView importBatch(ImportBatchRequest req) {
        validateRun(req.oldRun().readings(), "oldRun");
        validateRun(req.newRun().readings(), "newRun");
        if (!req.newRun().inspectedAt().isAfter(req.oldRun().inspectedAt())) {
            throw ApiException.badRequest("新回合检测日期必须晚于旧回合检测日期");
        }
        String fingerprint = Fingerprint.of(req);

        var existing = batchRepository.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        AlignmentOutcome outcome = matchingEngine.align(
                toInputs(req.oldRun().readings()), toInputs(req.newRun().readings()),
                req.oldRun().inspectedAt(), req.newRun().inspectedAt());

        long batchId;
        try {
            batchId = batchRepository.insert(req.requestId(), req.segmentId(),
                    req.oldRun().runId(), req.newRun().runId(),
                    req.oldRun().inspectedAt(), req.newRun().inspectedAt(), fingerprint);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已落库：按幂等规则重放或冲突
            BatchRow winner = batchRepository.findByRequestId(req.requestId())
                    .orElseThrow(() -> e);
            return replayOrConflict(winner, fingerprint);
        }

        Set<String> matchedOldKeys = new HashSet<>();
        Set<String> matchedNewKeys = new HashSet<>();
        for (MatchResult m : outcome.matches()) {
            matchedOldKeys.add(m.oldKey());
            matchedNewKeys.add(m.newKey());
        }
        Map<String, Long> oldIds = insertReadings(batchId, RunSide.OLD, req.oldRun().readings(), matchedOldKeys);
        Map<String, Long> newIds = insertReadings(batchId, RunSide.NEW, req.newRun().readings(), matchedNewKeys);
        for (MatchResult m : outcome.matches()) {
            matchRepository.insert(batchId, oldIds.get(m.oldKey()), newIds.get(m.newKey()),
                    m.distanceM(), m.growthRatePct(), m.grade());
        }
        return buildView(batchRepository.findById(batchId).orElseThrow());
    }

    /**
     * 查询批次详情。
     */
    @Transactional(readOnly = true)
    public BatchView getBatch(long id) {
        return buildView(batchRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + id)));
    }

    /**
     * 提交复核结论：仅 OPEN 批次可复核；封板后迟到复核拒绝且不改数据。
     * 通过批次行级锁与封板操作串行化，保证并发结果一致。
     */
    @Transactional
    public BatchView review(long batchId, ReviewRequest req) {
        BatchRow batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (batch.status() == BatchStatus.SEALED) {
            throw ApiException.conflict("批次已封板，拒绝复核修改");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (ReviewRequest.Item item : req.items()) {
            MatchRow match = matchRepository.findById(item.matchId())
                    .filter(m -> m.batchId() == batchId)
                    .orElseThrow(() -> ApiException.notFound("匹配对不存在: " + item.matchId()));
            if (match.grade() == Grade.STABLE) {
                throw ApiException.badRequest("稳定缺陷无需复核: " + item.matchId());
            }
            boolean applied = switch (item.action()) {
                case CONFIRM -> matchRepository.applyReview(
                        item.matchId(), ReviewStatus.CONFIRMED, null, item.reviewer(), now);
                case DOWNGRADE -> {
                    Grade target = parseDowngradeTarget(item);
                    if (target.ordinal() >= match.grade().ordinal()) {
                        throw ApiException.badRequest("降级目标必须低于系统分级: " + item.matchId());
                    }
                    yield matchRepository.applyReview(
                            item.matchId(), ReviewStatus.DOWNGRADED, target, item.reviewer(), now);
                }
            };
            if (!applied) {
                throw ApiException.conflict("匹配对已复核，拒绝重复结论: " + item.matchId());
            }
        }
        return buildView(batchRepository.findById(batchId).orElseThrow());
    }

    /**
     * 封板：要求全部增长/严重缺陷已复核；重复封板返回幂等成功；封板为终态。
     */
    @Transactional
    public BatchView seal(long batchId) {
        BatchRow batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (batch.status() == BatchStatus.SEALED) {
            return buildView(batch);
        }
        List<MatchRow> matches = matchRepository.findByBatchId(batchId);
        boolean hasPending = matches.stream().anyMatch(m ->
                m.grade() != Grade.STABLE && m.reviewStatus() == ReviewStatus.PENDING);
        if (hasPending) {
            throw ApiException.conflict("存在未复核的增长/严重缺陷，禁止封板");
        }
        if (!batchRepository.seal(batchId, batch.version(), LocalDateTime.now(clock))) {
            throw ApiException.conflict("批次状态已变更，封板失败");
        }
        return buildView(batchRepository.findById(batchId).orElseThrow());
    }

    private BatchView replayOrConflict(BatchRow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在且请求参数不一致");
        }
        return buildView(existing);
    }

    private static void validateRun(List<ReadingPayload> readings, String runName) {
        Set<String> keys = new HashSet<>();
        for (ReadingPayload r : readings) {
            if (!keys.add(r.readingKey())) {
                throw ApiException.badRequest(runName + " 内读数键重复: " + r.readingKey());
            }
        }
    }

    private static List<ReadingInput> toInputs(List<ReadingPayload> readings) {
        return readings.stream()
                .map(r -> new ReadingInput(r.readingKey(), r.stationM(), r.depthPct()))
                .toList();
    }

    private Map<String, Long> insertReadings(long batchId, RunSide side, List<ReadingPayload> readings,
                                             Set<String> matchedKeys) {
        Map<String, Long> ids = new HashMap<>();
        for (ReadingPayload r : readings) {
            long id = readingRepository.insert(batchId, side, r.readingKey(),
                    r.stationM(), r.depthPct(), matchedKeys.contains(r.readingKey()));
            ids.put(r.readingKey(), id);
        }
        return ids;
    }

    private static Grade parseDowngradeTarget(ReviewRequest.Item item) {
        if (item.downgradedGrade() == null || item.downgradedGrade().isBlank()) {
            throw ApiException.badRequest("DOWNGRADE 必须提供 downgradedGrade: " + item.matchId());
        }
        try {
            return Grade.valueOf(item.downgradedGrade().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("非法降级目标分级: " + item.downgradedGrade());
        }
    }

    private BatchView buildView(BatchRow batch) {
        List<ReadingRow> readings = readingRepository.findByBatchId(batch.id());
        Map<Long, String> keyById = new HashMap<>();
        List<String> newFindings = new ArrayList<>();
        List<String> disappeared = new ArrayList<>();
        for (ReadingRow r : readings) {
            keyById.put(r.id(), r.readingKey());
            if (!r.matched()) {
                if (r.runSide() == RunSide.NEW) {
                    newFindings.add(r.readingKey());
                } else {
                    disappeared.add(r.readingKey());
                }
            }
        }
        newFindings.sort(String::compareTo);
        disappeared.sort(String::compareTo);

        List<MatchView> matches = matchRepository.findByBatchId(batch.id()).stream()
                .sorted(Comparator.comparing(MatchRow::id))
                .map(m -> new MatchView(m.id(), keyById.get(m.oldReadingId()), keyById.get(m.newReadingId()),
                        m.distanceM(), m.growthRatePct(), m.grade().name(), m.effectiveGrade().name(),
                        m.reviewStatus().name(), m.reviewer(), m.reviewedAt()))
                .toList();
        return new BatchView(batch.id(), batch.requestId(), batch.segmentId(),
                batch.oldRunId(), batch.newRunId(), batch.oldInspectedAt(), batch.newInspectedAt(),
                batch.status().name(), matches, newFindings, disappeared,
                batch.createdAt(), batch.sealedAt());
    }
}
