package com.example.starter.ili;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.ili.AlignmentRepository.BatchRow;
import com.example.starter.ili.AlignmentRepository.MatchRow;
import com.example.starter.ili.AlignmentRepository.ReadingRow;
import com.example.starter.ili.BatchSubmitRequest.DefectReading;

/**
 * ILI 缺陷对齐与分级复核核心服务。
 *
 * <p>幂等：批次按 requestId 幂等，指纹覆盖 requestId、管段、两回合标识与全部缺陷读数的
 * 规范化摘要；同键同参返回首次结果，同键异参返回 409 且不写入部分结果。
 *
 * <p>并发：同键提交、复核与封板均通过对批次行的 {@code SELECT ... FOR UPDATE}
 * 串行化；封板另以条件更新兜底，保证封板后不会再写入复核结论。
 */
@Service
public class AlignmentService {

    /** 里程对齐容差：±0.5 m，恰好 0.5 m 视为匹配成功。 */
    static final BigDecimal TOLERANCE_M = new BigDecimal("0.5");

    private final AlignmentRepository repo;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AlignmentService(AlignmentRepository repo,
                            org.springframework.transaction.PlatformTransactionManager txManager,
                            Clock clock) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 提交对齐批次：整体导入两个回合缺陷清单、执行对齐与分级，整体成功或整体回滚。
     */
    public BatchView submit(BatchSubmitRequest req) {
        validate(req);
        String fingerprint = fingerprint(req);
        try {
            return tx.execute(status -> doSubmit(req, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发同键提交在唯一约束上冲突：回查首次写入的结果再判定。
            BatchRow existing = repo.findBatchByRequestId(req.requestId())
                    .orElseThrow(() -> e);
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且请求参数不一致");
            }
            return toView(existing);
        }
    }

    /**
     * 查询批次视图。
     */
    public BatchView getBatch(long batchId) {
        BatchRow batch = repo.lockBatchById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        return toView(batch);
    }

    /**
     * 提交复核结论：仅开放批次、仅增长/严重缺陷；封板后迟到复核拒绝且不改数据。
     */
    public BatchView review(long batchId, long matchId, ReviewRequest req) {
        return tx.execute(status -> {
            BatchRow batch = repo.lockBatchById(batchId)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
            if (batch.status() == BatchStatus.SEALED) {
                throw ApiException.conflict("批次已封板，拒绝复核修改");
            }
            MatchRow match = repo.findMatch(batchId, matchId)
                    .orElseThrow(() -> ApiException.notFound("匹配对不存在: " + matchId));
            if (match.grade() == Grade.STABLE) {
                throw ApiException.unprocessable("稳定缺陷无需复核");
            }
            ReviewStatus reviewStatus;
            Grade finalGrade;
            switch (req.action()) {
                case CONFIRM -> {
                    reviewStatus = ReviewStatus.CONFIRMED;
                    finalGrade = match.grade();
                }
                case DOWNGRADE -> {
                    Grade target = req.downgradedGrade();
                    if (target == null) {
                        throw ApiException.unprocessable("降级复核必须给出目标等级");
                    }
                    if (target.ordinal() >= match.grade().ordinal()) {
                        throw ApiException.unprocessable("降级目标等级必须低于原分级");
                    }
                    reviewStatus = ReviewStatus.DOWNGRADED;
                    finalGrade = target;
                }
                default -> throw ApiException.unprocessable("未知复核动作");
            }
            repo.updateMatchReview(matchId, reviewStatus, finalGrade, req.reviewer(),
                    LocalDateTime.now(clock));
            return toView(repo.lockBatchById(batchId).orElseThrow());
        });
    }

    /**
     * 封板：终态。重复封板幂等成功；并发封板与复核通过行锁形成一致结果。
     */
    public BatchView seal(long batchId) {
        return tx.execute(status -> {
            BatchRow batch = repo.lockBatchById(batchId)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
            if (batch.status() == BatchStatus.OPEN) {
                repo.sealBatchIfOpen(batchId, LocalDateTime.now(clock));
                batch = repo.lockBatchById(batchId).orElseThrow();
            }
            return toView(batch);
        });
    }

    private BatchView doSubmit(BatchSubmitRequest req, String fingerprint) {
        var existing = repo.lockBatchByRequestId(req.requestId());
        if (existing.isPresent()) {
            BatchRow batch = existing.get();
            if (!batch.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且请求参数不一致");
            }
            return toView(batch);
        }

        List<AlignedPair> pairs = align(req.oldRun().defects(), req.newRun().defects());
        double years = yearsBetween(req);

        long batchId = repo.insertBatch(req.requestId(), fingerprint, req.segmentId(),
                req.oldRun().runId(), req.newRun().runId(),
                req.oldRun().runDate(), req.newRun().runDate(),
                LocalDateTime.now(clock));

        Set<String> matchedOld = new HashSet<>();
        Set<String> matchedNew = new HashSet<>();
        for (AlignedPair p : pairs) {
            matchedOld.add(p.oldDefect().defectRef());
            matchedNew.add(p.newDefect().defectRef());
        }

        Map<String, Long> oldIds = new HashMap<>();
        Map<String, Long> newIds = new HashMap<>();
        for (DefectReading d : req.oldRun().defects()) {
            MatchOutcome outcome = matchedOld.contains(d.defectRef())
                    ? MatchOutcome.MATCHED : MatchOutcome.DISAPPEARED;
            oldIds.put(d.defectRef(), repo.insertReading(
                    batchId, "OLD", d.defectRef(), d.mileageM(), d.depthPct(), outcome));
        }
        for (DefectReading d : req.newRun().defects()) {
            MatchOutcome outcome = matchedNew.contains(d.defectRef())
                    ? MatchOutcome.MATCHED : MatchOutcome.NEW_FOUND;
            newIds.put(d.defectRef(), repo.insertReading(
                    batchId, "NEW", d.defectRef(), d.mileageM(), d.depthPct(), outcome));
        }

        for (AlignedPair p : pairs) {
            double rate = (p.newDefect().depthPct().doubleValue()
                    - p.oldDefect().depthPct().doubleValue()) / years;
            Grade grade = Grade.of(rate);
            ReviewStatus reviewStatus = grade == Grade.STABLE
                    ? ReviewStatus.NOT_REQUIRED : ReviewStatus.PENDING;
            repo.insertMatch(batchId,
                    oldIds.get(p.oldDefect().defectRef()),
                    newIds.get(p.newDefect().defectRef()),
                    p.diff().setScale(3, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(rate).setScale(3, RoundingMode.HALF_UP),
                    grade, reviewStatus, grade);
        }
        return toView(repo.lockBatchById(batchId).orElseThrow());
    }

    /**
     * 里程容差对齐：候选对按里程差升序贪心指派，一对多取里程差最小者，
     * 同一缺陷不会出现在两个匹配对中。
     */
    static List<AlignedPair> align(List<DefectReading> oldDefects, List<DefectReading> newDefects) {
        record Candidate(DefectReading oldD, DefectReading newD, BigDecimal diff) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (DefectReading o : oldDefects) {
            for (DefectReading n : newDefects) {
                BigDecimal diff = o.mileageM().subtract(n.mileageM()).abs();
                if (diff.compareTo(TOLERANCE_M) <= 0) {
                    candidates.add(new Candidate(o, n, diff));
                }
            }
        }
        candidates.sort(Comparator.comparing(Candidate::diff)
                .thenComparing(c -> c.oldD().defectRef())
                .thenComparing(c -> c.newD().defectRef()));

        Set<String> usedOld = new HashSet<>();
        Set<String> usedNew = new HashSet<>();
        List<AlignedPair> pairs = new ArrayList<>();
        for (Candidate c : candidates) {
            if (usedOld.add(c.oldD().defectRef()) && usedNew.add(c.newD().defectRef())) {
                pairs.add(new AlignedPair(c.oldD(), c.newD(), c.diff()));
            }
        }
        return pairs;
    }

    /**
     * 一对已对齐缺陷。
     */
    record AlignedPair(DefectReading oldDefect, DefectReading newDefect, BigDecimal diff) {
    }

    private static double yearsBetween(BatchSubmitRequest req) {
        long days = ChronoUnit.DAYS.between(req.oldRun().runDate(), req.newRun().runDate());
        return days / 365.0;
    }

    private static void validate(BatchSubmitRequest req) {
        if (!req.newRun().runDate().isAfter(req.oldRun().runDate())) {
            throw ApiException.unprocessable("新回合日期必须晚于旧回合日期");
        }
        checkDuplicateRefs(req.oldRun().defects(), "旧回合");
        checkDuplicateRefs(req.newRun().defects(), "新回合");
        for (var run : List.of(req.oldRun(), req.newRun())) {
            for (DefectReading d : run.defects()) {
                if (d.depthPct().compareTo(BigDecimal.ZERO) < 0
                        || d.depthPct().compareTo(new BigDecimal("100")) > 0) {
                    throw ApiException.unprocessable("缺陷深度须在 0-100 之间: " + d.defectRef());
                }
            }
        }
    }

    private static void checkDuplicateRefs(List<DefectReading> defects, String side) {
        Set<String> seen = new HashSet<>();
        for (DefectReading d : defects) {
            if (!seen.add(d.defectRef())) {
                throw ApiException.unprocessable(side + "存在重复缺陷编号: " + d.defectRef());
            }
        }
    }

    /**
     * 幂等指纹：requestId、管段、两回合标识与全部缺陷读数规范化摘要的 SHA-256。
     */
    static String fingerprint(BatchSubmitRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.requestId()).append('|')
                .append(req.segmentId()).append('|')
                .append(req.oldRun().runId()).append('|')
                .append(req.oldRun().runDate()).append('|')
                .append(req.newRun().runId()).append('|')
                .append(req.newRun().runDate()).append('|');
        appendDefects(sb, "OLD", req.oldRun().defects());
        appendDefects(sb, "NEW", req.newRun().defects());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void appendDefects(StringBuilder sb, String side, List<DefectReading> defects) {
        sb.append(side).append(':');
        defects.stream()
                .sorted(Comparator.comparing(DefectReading::defectRef))
                .forEach(d -> sb.append(d.defectRef()).append('=')
                        .append(d.mileageM().stripTrailingZeros().toPlainString()).append(',')
                        .append(d.depthPct().stripTrailingZeros().toPlainString()).append(';'));
        sb.append('|');
    }

    private BatchView toView(BatchRow batch) {
        List<BatchView.MatchView> matches = repo.listMatches(batch.id()).stream()
                .map(m -> new BatchView.MatchView(
                        m.id(), m.oldDefectRef(), m.newDefectRef(),
                        m.oldMileageM(), m.newMileageM(), m.mileageDiffM(),
                        m.oldDepthPct(), m.newDepthPct(), m.growthRate(),
                        m.grade(), m.reviewStatus(), m.finalGrade(),
                        m.reviewedBy(), m.reviewedAt()))
                .toList();
        List<BatchView.ReadingView> unmatched = repo.listUnmatchedReadings(batch.id()).stream()
                .map(r -> new BatchView.ReadingView(
                        r.runSide(), r.defectRef(), r.mileageM(), r.depthPct(), r.outcome()))
                .toList();
        return new BatchView(batch.id(), batch.requestId(), batch.segmentId(),
                batch.oldRunId(), batch.newRunId(), batch.oldRunDate(), batch.newRunDate(),
                batch.status(), batch.createdAt(), batch.sealedAt(), matches, unmatched);
    }
}
