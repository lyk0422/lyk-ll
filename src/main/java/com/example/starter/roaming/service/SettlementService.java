package com.example.starter.roaming.service;

import com.example.starter.roaming.api.SealBatchRequest;
import com.example.starter.roaming.api.SettlementBatchResponse;
import com.example.starter.roaming.api.WriteOffRequest;
import com.example.starter.roaming.api.WriteOffResponse;
import com.example.starter.roaming.error.ConflictException;
import com.example.starter.roaming.error.NotFoundException;
import com.example.starter.roaming.repo.MeterSnapshotRepository.SnapshotRow;
import com.example.starter.roaming.repo.ReferenceDataRepository;
import com.example.starter.roaming.repo.SessionRepository;
import com.example.starter.roaming.repo.SessionRepository.SessionRow;
import com.example.starter.roaming.repo.MeterSnapshotRepository;
import com.example.starter.roaming.repo.SettlementRepository;
import com.example.starter.roaming.repo.SettlementRepository.BatchRow;
import com.example.starter.roaming.repo.SettlementRepository.DayLockRow;
import com.example.starter.roaming.repo.SettlementRepository.DetailRow;
import com.example.starter.roaming.repo.SettlementRepository.SuspenseRow;
import com.example.starter.roaming.repo.SettlementRepository.WriteOffRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨运营商按 UTC 日清分。
 * <p>封存以日清分行锁与“会话结束”串行：先提交结束的会话计入本批，
 * 后到者留待后续处理且绝不重复归属；批内合计必须等于明细之和；
 * 封存为终态，重复封存返回首次清分单且不重新计算。</p>
 */
@Service
public class SettlementService {

    private final Clock clock;
    private final SessionRepository sessionRepository;
    private final MeterSnapshotRepository meterRepository;
    private final SettlementRepository settlementRepository;
    private final ReferenceDataRepository referenceDataRepository;
    private final IdempotencySupport idempotency;

    public SettlementService(Clock clock,
                             SessionRepository sessionRepository,
                             MeterSnapshotRepository meterRepository,
                             SettlementRepository settlementRepository,
                             ReferenceDataRepository referenceDataRepository,
                             IdempotencySupport idempotency) {
        this.clock = clock;
        this.sessionRepository = sessionRepository;
        this.meterRepository = meterRepository;
        this.settlementRepository = settlementRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.idempotency = idempotency;
    }

    /** 封存指定 UTC 日清分批（终态）。 */
    @Transactional
    public SettlementBatchResponse seal(SealBatchRequest request) {
        var result = idempotency.execute(
                request.requestId(), "SEAL", request, 200, SettlementBatchResponse.class,
                () -> doSeal(request));
        SettlementBatchResponse body = result.body();
        // 同 requestId 重放或对已封存日重复封存，均返回首次清分单且不重新计算。
        return new SettlementBatchResponse(body.batchId(), body.batchNo(), body.settlementDay(),
                body.status(), body.sessionCount(), body.totalEnergyKwh(), body.totalReceivable(),
                body.totalPayable(), body.sealedAt(), body.replayed() || result.replayed(),
                body.details());
    }

    private SettlementBatchResponse doSeal(SealBatchRequest request) {
        Instant now = Instant.now(clock);
        settlementRepository.insertDayLockIfAbsent(request.settlementDay());
        DayLockRow lock = settlementRepository.lockDay(request.settlementDay());
        if (lock.sealed()) {
            // 封存为终态：不同 requestId 重复封存也返回首次清分单，不重新计算。
            BatchRow first = settlementRepository.findBatchById(lock.batchId())
                    .or(() -> settlementRepository.findBatchByDay(request.settlementDay()))
                    .orElseThrow(() -> new IllegalStateException("已封存日缺少清分批"));
            return toResponse(first, true);
        }

        List<SessionRow> candidates =
                sessionRepository.findEndedUnassignedByDay(request.settlementDay());

        BigDecimal totalEnergy = BigDecimal.ZERO;
        BigDecimal totalReceivable = BigDecimal.ZERO;
        BigDecimal totalPayable = BigDecimal.ZERO;
        List<Detail> computed = new ArrayList<>();

        for (SessionRow session : candidates) {
            var home = referenceDataRepository.findOperator(session.homeOperatorId())
                    .orElseThrow(() -> new IllegalStateException("归属运营商不存在"));
            var serving = referenceDataRepository.findOperator(session.servingOperatorId())
                    .orElseThrow(() -> new IllegalStateException("服务运营商不存在"));

            // 封存时刻之前到达的计量快照（含结束后补传）计入本批。
            SnapshotRow latest = meterRepository.findLatest(session.sessionNo()).orElse(null);
            BigDecimal energy = latest == null ? BigDecimal.ZERO : latest.energyKwh();
            int accountedSeq = latest == null ? -1 : latest.readingSeq();

            BigDecimal receivable = MoneyUtil.yuan(energy.multiply(home.tariffReceivable()));
            BigDecimal payable = MoneyUtil.yuan(energy.multiply(serving.tariffPayable()));

            computed.add(new Detail(session, accountedSeq, energy, receivable, payable));
            totalEnergy = totalEnergy.add(energy);
            totalReceivable = totalReceivable.add(receivable);
            totalPayable = totalPayable.add(payable);
        }

        String batchNo = "BATCH-" + request.settlementDay() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        BatchRow batch = new BatchRow(null, batchNo, request.settlementDay(), "SEALED",
                computed.size(), totalEnergy, totalReceivable, totalPayable, now);
        Long batchId = settlementRepository.insertBatch(batch);

        for (Detail d : computed) {
            int updated = sessionRepository.assignBatchIfUnassigned(
                    d.session().id(), batchId, d.accountedSeq(), d.energy(), now);
            if (updated == 0) {
                // 日锁已排除并发；若仍已归属他批，说明数据被破坏，整体回滚，绝不重复计入。
                throw new ConflictException("会话已归属其他批次：" + d.session().sessionNo());
            }
            settlementRepository.insertDetail(new DetailRow(
                    null, batchId, d.session().id(), d.session().sessionNo(),
                    d.session().homeOperatorId(), d.session().servingOperatorId(),
                    d.energy(), d.receivable(), d.payable()));
        }

        settlementRepository.markDaySealed(request.settlementDay(), batchId);

        // 批内合计必须等于明细之和（金额为各自四舍五入后的明细之和）。
        BatchRow saved = settlementRepository.findBatchById(batchId).orElseThrow();
        SettlementBatchResponse response = toResponse(saved, false);
        assertTotals(response);
        return response;
    }

    /** 人工核销挂账并留痕；同一挂账只允许核销一次。 */
    @Transactional
    public WriteOffResponse writeOff(String suspenseNo, WriteOffRequest request) {
        var result = idempotency.execute(
                request.requestId(), "WRITE_OFF", java.util.List.of(suspenseNo, request), 200,
                WriteOffResponse.class, () -> doWriteOff(suspenseNo, request));
        WriteOffResponse body = result.body();
        return new WriteOffResponse(body.suspenseNo(), body.status(), body.energyDeltaKwh(),
                body.receivableDelta(), body.payableDelta(), body.operatorName(),
                body.writtenOffAt(), result.replayed());
    }

    private WriteOffResponse doWriteOff(String suspenseNo, WriteOffRequest request) {
        Instant now = Instant.now(clock);
        SuspenseRow suspense = settlementRepository.findSuspenseByNo(suspenseNo)
                .orElseThrow(() -> new NotFoundException("挂账不存在：" + suspenseNo));
        if ("WRITTEN_OFF".equals(suspense.status())) {
            throw new ConflictException("挂账已核销，不允许重复核销：" + suspenseNo);
        }
        int updated = settlementRepository.markSuspenseWrittenOff(suspense.id(), now);
        if (updated == 0) {
            // 与并发核销竞争：唯一核销由条件更新 + 唯一约束双重保证。
            throw new ConflictException("挂账已被其他操作核销：" + suspenseNo);
        }
        settlementRepository.insertWriteOff(new WriteOffRow(
                null, suspense.id(), request.requestId(), request.operatorName(),
                request.remark(), now));
        return new WriteOffResponse(suspense.suspenseNo(), "WRITTEN_OFF",
                suspense.energyDeltaKwh(), suspense.receivableDelta(), suspense.payableDelta(),
                request.operatorName(), now, false);
    }

    private void assertTotals(SettlementBatchResponse response) {
        BigDecimal energy = BigDecimal.ZERO;
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;
        for (SettlementBatchResponse.Detail d : response.details()) {
            energy = energy.add(d.energyKwh());
            receivable = receivable.add(d.receivableAmount());
            payable = payable.add(d.payableAmount());
        }
        if (energy.compareTo(response.totalEnergyKwh()) != 0
                || receivable.compareTo(response.totalReceivable()) != 0
                || payable.compareTo(response.totalPayable()) != 0) {
            throw new IllegalStateException("清分批合计与明细之和不一致");
        }
    }

    private SettlementBatchResponse toResponse(BatchRow batch, boolean replayed) {
        List<SettlementBatchResponse.Detail> details =
                settlementRepository.findDetailsByBatch(batch.id()).stream()
                        .map(d -> new SettlementBatchResponse.Detail(
                                d.sessionNo(), d.homeOperatorId(), d.servingOperatorId(),
                                d.energyKwh(), d.receivableAmount(), d.payableAmount()))
                        .toList();
        return new SettlementBatchResponse(batch.id(), batch.batchNo(), batch.settlementDay(),
                batch.status(), batch.sessionCount(), batch.totalEnergyKwh(),
                batch.totalReceivable(), batch.totalPayable(), batch.sealedAt(), replayed, details);
    }

    /** 封存计算中间结果。 */
    private record Detail(SessionRow session, int accountedSeq, BigDecimal energy,
                          BigDecimal receivable, BigDecimal payable) {
    }
}
