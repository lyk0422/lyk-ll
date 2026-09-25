package com.example.starter.irrigation.service;

import com.example.starter.irrigation.dto.SettlementContracts.SealRequest;
import com.example.starter.irrigation.dto.SettlementContracts.SettlementDetailView;
import com.example.starter.irrigation.dto.SettlementContracts.SettlementView;
import com.example.starter.irrigation.error.BizException;
import com.example.starter.irrigation.repo.GroupRepository;
import com.example.starter.irrigation.repo.MeteringRepository;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.repo.SettlementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 欠供结转结算封存服务：轮次结束后自动结算并封存，封存为终态，重复封存幂等。
 */
@Service
public class SettlementService {

    private final GroupRepository groupRepository;
    private final PlanRepository planRepository;
    private final MeteringRepository meteringRepository;
    private final SettlementRepository settlementRepository;
    private final TransactionTemplate transactionTemplate;

    public SettlementService(GroupRepository groupRepository,
                             PlanRepository planRepository,
                             MeteringRepository meteringRepository,
                             SettlementRepository settlementRepository,
                             TransactionTemplate transactionTemplate) {
        this.groupRepository = groupRepository;
        this.planRepository = planRepository;
        this.meteringRepository = meteringRepository;
        this.settlementRepository = settlementRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 结算并封存轮次。以封存时该轮最后一次已确认计量快照为准，结果稳定可复算；
     * 重复封存返回首次结果的幂等成功。
     */
    public SettlementView seal(SealRequest request) {
        var existing = settlementRepository.findByGroupAndRound(request.groupId(), request.roundNo());
        if (existing.isPresent()) {
            return toView(existing.get(), true);
        }
        try {
            return transactionTemplate.execute(status -> doSeal(request));
        } catch (DuplicateKeyException e) {
            // 并发封存：唯一约束保证只有一个结算落库，其余返回首次结果。
            SettlementRepository.SettlementRow row = settlementRepository
                    .findByGroupAndRound(request.groupId(), request.roundNo())
                    .orElseThrow(() -> BizException.conflict("封存状态异常，请重试"));
            return toView(row, true);
        }
    }

    public SettlementView get(long groupId, int roundNo) {
        SettlementRepository.SettlementRow row = settlementRepository.findByGroupAndRound(groupId, roundNo)
                .orElseThrow(() -> BizException.notFound(
                        "结算不存在: group=" + groupId + ", round=" + roundNo));
        return toView(row, false);
    }

    private SettlementView doSeal(SealRequest request) {
        groupRepository.findGroupById(request.groupId())
                .orElseThrow(() -> BizException.notFound("轮灌组不存在: " + request.groupId()));
        var existing = settlementRepository.findByGroupAndRound(request.groupId(), request.roundNo());
        if (existing.isPresent()) {
            return toView(existing.get(), true);
        }
        // 与计量上报共用生效指针行锁：封存完成后，后续计量只会落入迟到事件。
        Long planVersionId = planRepository
                .lockEffectivePlanVersionId(request.groupId(), request.roundNo())
                .orElseThrow(() -> BizException.conflict(
                        "该轮次无生效计划，无法封存: group=" + request.groupId()
                                + ", round=" + request.roundNo()));
        List<PlanRepository.PlanDetailRow> planDetails = planRepository.findDetailsByVersionId(planVersionId);
        Map<Long, Long> metered = meteringRepository.findByGroupAndRound(request.groupId(), request.roundNo());

        long settlementId = settlementRepository.insert(request.groupId(), request.roundNo());
        for (PlanRepository.PlanDetailRow detail : planDetails) {
            long planned = detail.plannedM3();
            long actual = metered.getOrDefault(detail.outletId(), 0L);
            long deficit = Math.max(0L, planned - actual);
            long carryover = AllocationCalculator.carryover(planned, deficit);
            long voided = deficit - carryover;
            settlementRepository.insertDetail(settlementId, detail.outletId(), planned, actual,
                    deficit, carryover, voided);
        }
        return toView(settlementRepository.findByGroupAndRound(request.groupId(), request.roundNo())
                .orElseThrow(), false);
    }

    private SettlementView toView(SettlementRepository.SettlementRow row, boolean replayed) {
        List<SettlementDetailView> details = settlementRepository.findDetails(row.id()).stream()
                .map(d -> new SettlementDetailView(d.outletId(), d.plannedM3(), d.actualM3(),
                        d.deficitM3(), d.carryoverM3(), d.voidedM3()))
                .toList();
        return new SettlementView(row.id(), row.groupId(), row.roundNo(), replayed, details);
    }
}
