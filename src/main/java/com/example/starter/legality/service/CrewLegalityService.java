package com.example.starter.legality.service;

import com.example.starter.legality.api.BatchCheckRequest;
import com.example.starter.legality.api.BatchCheckResponse;
import com.example.starter.legality.api.DelayRequest;
import com.example.starter.legality.api.DelayResponse;
import com.example.starter.legality.api.FinalizeResponse;
import com.example.starter.legality.api.ReportRequest;
import com.example.starter.legality.api.ReportResponse;
import com.example.starter.legality.domain.BatchStatus;
import com.example.starter.legality.domain.CheckBatch;
import com.example.starter.legality.domain.DutyRecord;
import com.example.starter.legality.repo.DutyRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 值勤合规应用服务：报到、整组校验、延误延长、封板。
 *
 * <p>所有判定与写入在事务内完成；进程内 {@link KeyLock} 按机组成员串行化报到，
 * 按固定锁序（delay -> crew）串行化延误与级联重判，适配单 JVM 嵌入式 H2 场景。
 */
@Service
public class CrewLegalityService {

    private final DutyRepository repository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final KeyLock crewLocks = new KeyLock();
    private final KeyLock delayLocks = new KeyLock();

    public CrewLegalityService(DutyRepository repository,
                               Clock clock,
                               org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repository = repository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    /** 单条值勤报到：requestId 幂等，同键异参 409 且不写入。 */
    public ReportResponse report(ReportRequest request) {
        // 串行化同一机组成员的并发报到，保证同一时段只受理一条，事务包在锁内。
        String crewKey = crewKey(request.crewId());
        crewLocks.lock(crewKey);
        try {
            return transactionTemplate.execute(status -> doReport(request));
        } finally {
            crewLocks.unlock(crewKey);
        }
    }

    private ReportResponse doReport(ReportRequest request) {
        OffsetDateTime reportUtc = utc(request.reportTime());
        OffsetDateTime endUtc = utc(request.scheduledEndTime());
        if (endUtc.isBefore(reportUtc)) {
            throw new BadRequestException("scheduledEndTime must not be before reportTime");
        }
        String batchId = normalizeBatchId(request.batchId());
        ensureBatchOpen(batchId);

        // 幂等：同 requestId 同参返回首次判定；同键异参 409 不写入。
        DutyRecord existing = repository.findByRequestId(request.requestId());
        if (existing != null) {
            if (!sameFingerprint(existing, request.crewId(), request.flightNo(), reportUtc)) {
                throw new IdempotentConflictException(request.requestId());
            }
            return toReportResponse(existing, true);
        }

        List<String> reasons = evaluateMember(request.crewId(), reportUtc, endUtc, 0, request.requestId());
        DutyRecord record = new DutyRecord(
                request.requestId(), batchId, request.crewId(), request.flightNo(),
                reportUtc, endUtc, 0, reasons.isEmpty(), joinReasons(reasons), nowUtc());
        try {
            repository.insertDuty(record);
        } catch (DuplicateKeyException ex) {
            // 并发：requestId 唯一键或成员报到时刻唯一键冲突，只接受首条，其余按幂等重放。
            DutyRecord winner = repository.findByRequestId(request.requestId());
            if (winner == null) {
                winner = repository.findBySlot(request.crewId(), reportUtc);
            }
            if (winner == null) {
                throw ex;
            }
            if (winner.batchId() != null && !winner.batchId().equals(batchId)) {
                throw new IdempotentConflictException(request.requestId());
            }
            return toReportResponse(winner, true);
        }

        if (batchId != null) {
            repository.linkDutyToBatch(batchId, request.requestId());
            recomputeBatchVerdict(batchId);
        }
        return toReportResponse(record, false);
    }

    /** 整组校验：每个成员独立判定，同一事务内给出整组结果；任一成员违规则整组违规。 */
    public BatchCheckResponse checkBatch(BatchCheckRequest request) {
        for (BatchCheckRequest.MemberDuty member : request.members()) {
            if (utc(member.scheduledEndTime()).isBefore(utc(member.reportTime()))) {
                throw new BadRequestException(
                        "scheduledEndTime must not be before reportTime: " + member.requestId());
            }
        }
        // 有序获取涉及成员锁（与单条报到互斥、避免死锁），整组判定仍在同一事务内完成。
        List<String> orderedCrew = request.members().stream()
                .map(BatchCheckRequest.MemberDuty::crewId)
                .distinct().sorted().toList();
        orderedCrew.forEach(crew -> crewLocks.lock(crewKey(crew)));
        try {
            return transactionTemplate.execute(status -> doCheckBatch(request));
        } finally {
            for (int i = orderedCrew.size() - 1; i >= 0; i--) {
                crewLocks.unlock(crewKey(orderedCrew.get(i)));
            }
        }
    }

    private BatchCheckResponse doCheckBatch(BatchCheckRequest request) {
        String batchId = request.batchId();

        CheckBatch batch = repository.findBatch(batchId);
        if (batch != null && batch.status() == BatchStatus.FINALIZED) {
            throw new BatchFinalizedException(batchId);
        }
        if (batch == null) {
            repository.insertBatch(new CheckBatch(
                    batchId, BatchStatus.CHECKED, true, nowUtc(), null));
        }

        for (BatchCheckRequest.MemberDuty member : request.members()) {
            acceptMemberDuty(batchId, member);
        }

        boolean groupLegal = recomputeBatchVerdict(batchId);
        return buildBatchResponse(batchId, groupLegal, BatchStatus.CHECKED);
    }

    /**
     * 将批次成员条目落库为值勤记录：幂等复用首条记录，同键异参 409，时段并发冲突只受理一条。
     */
    private void acceptMemberDuty(String batchId, BatchCheckRequest.MemberDuty member) {
        OffsetDateTime reportUtc = utc(member.reportTime());
        OffsetDateTime endUtc = utc(member.scheduledEndTime());

        DutyRecord existing = repository.findByRequestId(member.requestId());
        if (existing != null) {
            if (!sameFingerprint(existing, member.crewId(), member.flightNo(), reportUtc)) {
                throw new IdempotentConflictException(member.requestId());
            }
            if (existing.batchId() != null && !existing.batchId().equals(batchId)) {
                throw new IdempotentConflictException(member.requestId());
            }
            if (existing.batchId() == null) {
                repository.attachBatch(existing.requestId(), batchId);
            }
            repository.linkDutyToBatch(batchId, existing.requestId());
            return;
        }

        DutyRecord sameSlot = repository.findBySlot(member.crewId(), reportUtc);
        if (sameSlot != null) {
            if (sameSlot.batchId() != null && !sameSlot.batchId().equals(batchId)) {
                throw new IdempotentConflictException(member.requestId());
            }
            if (sameSlot.batchId() == null) {
                repository.attachBatch(sameSlot.requestId(), batchId);
            }
            repository.linkDutyToBatch(batchId, sameSlot.requestId());
            return;
        }

        List<String> reasons = evaluateMember(member.crewId(), reportUtc, endUtc, 0, member.requestId());
        DutyRecord record = new DutyRecord(
                member.requestId(), batchId, member.crewId(), member.flightNo(),
                reportUtc, endUtc, 0, reasons.isEmpty(), joinReasons(reasons), nowUtc());
        try {
            repository.insertDuty(record);
        } catch (DuplicateKeyException ex) {
            DutyRecord winner = repository.findByRequestId(member.requestId());
            if (winner == null) {
                winner = repository.findBySlot(member.crewId(), reportUtc);
            }
            if (winner == null) {
                throw ex;
            }
            if (winner.batchId() != null && !winner.batchId().equals(batchId)) {
                throw new IdempotentConflictException(member.requestId());
            }
            repository.linkDutyToBatch(batchId, winner.requestId());
            return;
        }
        repository.linkDutyToBatch(batchId, member.requestId());
    }

    /**
     * 延误延长：封板前应用并重判（同一事务内同步成员与整组结论）；
     * 封板后仅登记为迟到事件（applied=0），不推翻终态结论。
     */
    public DelayResponse submitDelay(DelayRequest request) {
        DutyRecord record = repository.findByRequestId(request.requestId());
        if (record == null) {
            throw new ResourceNotFoundException("duty not found: " + request.requestId());
        }
        boolean finalized = record.batchId() != null
                && repository.findBatch(record.batchId()).status() == BatchStatus.FINALIZED;
        LocalDate dutyDay = record.reportTime().toLocalDate();

        if (finalized) {
            return transactionTemplate.execute(status -> {
                repository.insertDelayEvent(request.requestId(), record.crewId(), dutyDay,
                        request.delayMinutes(), false, nowUtc());
                int appliedTotal = repository.sumAppliedDelayOnDate(
                        record.crewId(), dutyDay, "__none__");
                return new DelayResponse(request.requestId(), false, appliedTotal,
                        record.legal(), splitReasons(record.reasons()), true);
            });
        }

        // 串行化同成员的延误累计与级联重判（可能跨多个 UTC 日历日），事务边界包在锁内。
        // 锁序固定 delay -> crew，不存在反向获取，不会死锁。
        String delayKey = "delay:" + record.crewId();
        String crewKey = crewKey(record.crewId());
        delayLocks.lock(delayKey);
        crewLocks.lock(crewKey);
        try {
            return transactionTemplate.execute(status -> {
                DutyRecord current = repository.findByRequestId(request.requestId());
                int otherDelay = repository.sumAppliedDelayOnDate(
                        current.crewId(), dutyDay, current.requestId());
                int newTotal = current.delayMinutes() + request.delayMinutes();
                List<String> reasons = evaluateMember(
                        current.crewId(), current.reportTime(), current.scheduledEndTime(),
                        newTotal, current.requestId());
                repository.insertDelayEvent(current.requestId(), current.crewId(), dutyDay,
                        request.delayMinutes(), true, nowUtc());
                repository.updateDutyVerdict(current.requestId(), newTotal,
                        reasons.isEmpty(), joinReasons(reasons));

                Set<String> affectedBatches = new HashSet<>();
                // 较早执勤延长会改变后续执勤的休息期与 7 日窗口：按时间顺序级联重判未封板记录。
                for (DutyRecord later : repository.findDutiesAfter(
                        current.crewId(), current.reportTime())) {
                    if (later.batchId() != null
                            && repository.findBatch(later.batchId()).status() == BatchStatus.FINALIZED) {
                        continue;
                    }
                    List<String> laterReasons = evaluateMember(
                            later.crewId(), later.reportTime(), later.scheduledEndTime(),
                            later.delayMinutes(), later.requestId());
                    repository.updateDutyVerdict(later.requestId(), later.delayMinutes(),
                            laterReasons.isEmpty(), joinReasons(laterReasons));
                    if (later.batchId() != null) {
                        affectedBatches.add(later.batchId());
                    }
                }
                // 当前记录及全部级联记录所属批次都重建成员聚合与整组结论。
                if (current.batchId() != null) {
                    affectedBatches.add(current.batchId());
                }
                for (String affectedBatch : affectedBatches) {
                    recomputeBatchVerdict(affectedBatch);
                }
                return new DelayResponse(current.requestId(), true, otherDelay + newTotal,
                        reasons.isEmpty(), reasons, false);
            });
        } finally {
            crewLocks.unlock(crewKey);
            delayLocks.unlock(delayKey);
        }
    }

    /** 封板：结论终态；重复封板返回幂等成功。 */
    public FinalizeResponse finalizeBatch(String batchId) {
        return transactionTemplate.execute(status -> {
            CheckBatch batch = repository.findBatch(batchId);
            if (batch == null) {
                throw new ResourceNotFoundException("batch not found: " + batchId);
            }
            if (batch.status() == BatchStatus.FINALIZED) {
                return new FinalizeResponse(batchId, batch.legal(), true);
            }
            boolean groupLegal = recomputeBatchVerdict(batchId);
            repository.markFinalized(batchId, groupLegal, nowUtc());
            return new FinalizeResponse(batchId, groupLegal, false);
        });
    }

    /** 调用规则引擎评估单个成员：载入其报到时刻之前的历史记录与当日已有延长。 */
    private List<String> evaluateMember(String crewId, OffsetDateTime reportUtc,
                                        OffsetDateTime endUtc, int delayMinutes,
                                        String excludeRequestId) {
        List<DutyRecord> history = repository.findHistoryBefore(crewId, reportUtc);
        int priorDayDelay = repository.sumAppliedDelayOnDate(
                crewId, reportUtc.toLocalDate(), excludeRequestId);
        return LegalityRules.evaluate(reportUtc, endUtc, delayMinutes, history, priorDayDelay);
    }

    /**
     * 按批次内全部值勤记录重建成员聚合判定并写入整组结论：
     * 成员任一段违规则成员违规，任一成员违规则整组违规。
     */
    private boolean recomputeBatchVerdict(String batchId) {
        List<Object[]> aggregates = repository.aggregateBatchDuties(batchId);
        boolean groupLegal = true;
        for (Object[] row : aggregates) {
            String crewId = (String) row[0];
            boolean memberLegal = (Integer) row[1] == 1;
            String reasons = normalizeReasons((String) row[2]);
            repository.upsertBatchMember(batchId, crewId, memberLegal, reasons);
            groupLegal &= memberLegal;
        }
        repository.updateBatchVerdict(batchId, groupLegal);
        return groupLegal;
    }

    private BatchCheckResponse buildBatchResponse(String batchId, boolean groupLegal, BatchStatus status) {
        List<BatchCheckResponse.MemberResult> results = new ArrayList<>();
        for (Object[] row : repository.findBatchMembers(batchId)) {
            results.add(new BatchCheckResponse.MemberResult(
                    (String) row[0], (Integer) row[1] == 1, splitReasons((String) row[2])));
        }
        return new BatchCheckResponse(batchId, groupLegal, status.name(), results);
    }

    /** LISTAGG 跨段拼接的原因串可能含重复码，按出现顺序去重后再落库。 */
    private String normalizeReasons(String aggregated) {
        if (aggregated == null || aggregated.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(splitReasons(aggregated));
        return joinReasons(new ArrayList<>(unique));
    }

    /** 校验批次处于可写入状态：已封板批次的补报/改签一律拒绝，须走新批次。 */
    private void ensureBatchOpen(String batchId) {
        if (batchId == null) {
            return;
        }
        CheckBatch batch = repository.findBatch(batchId);
        if (batch == null) {
            repository.insertBatch(new CheckBatch(
                    batchId, BatchStatus.CHECKED, true, nowUtc(), null));
            return;
        }
        if (batch.status() == BatchStatus.FINALIZED) {
            throw new BatchFinalizedException(batchId);
        }
    }

    private boolean sameFingerprint(DutyRecord record, String crewId, String flightNo,
                                    OffsetDateTime reportUtc) {
        return record.crewId().equals(crewId)
                && record.flightNo().equals(flightNo)
                && record.reportTime().toInstant().equals(reportUtc.toInstant());
    }

    private ReportResponse toReportResponse(DutyRecord record, boolean replayed) {
        return new ReportResponse(record.requestId(), record.batchId(), record.crewId(),
                record.flightNo(), record.legal(), splitReasons(record.reasons()),
                record.delayMinutes(), replayed);
    }

    private static OffsetDateTime utc(OffsetDateTime time) {
        return time.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private OffsetDateTime nowUtc() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private String normalizeBatchId(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return null;
        }
        return batchId;
    }

    private String crewKey(String crewId) {
        return "crew:" + crewId;
    }

    static String joinReasons(List<String> reasons) {
        return String.join(",", reasons);
    }

    static List<String> splitReasons(String reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(reasons.split(","));
    }
}
