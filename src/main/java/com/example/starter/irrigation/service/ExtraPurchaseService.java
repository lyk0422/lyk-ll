package com.example.starter.irrigation.service;

import com.example.starter.irrigation.dto.ExtraPurchaseContracts.ExtraAllocationView;
import com.example.starter.irrigation.dto.ExtraPurchaseContracts.ExtraPurchaseRequest;
import com.example.starter.irrigation.dto.ExtraPurchaseContracts.ExtraPurchaseView;
import com.example.starter.irrigation.error.BizException;
import com.example.starter.irrigation.repo.ExtraPurchaseRepository;
import com.example.starter.irrigation.repo.GroupRepository;
import com.example.starter.irrigation.repo.MeteringRepository;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.repo.SettlementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 临时加购服务：加购水量优先满足欠供斗口，按欠供比例分配；requestId 幂等。
 */
@Service
public class ExtraPurchaseService {

    private final GroupRepository groupRepository;
    private final PlanRepository planRepository;
    private final MeteringRepository meteringRepository;
    private final SettlementRepository settlementRepository;
    private final ExtraPurchaseRepository extraPurchaseRepository;

    public ExtraPurchaseService(GroupRepository groupRepository,
                                PlanRepository planRepository,
                                MeteringRepository meteringRepository,
                                SettlementRepository settlementRepository,
                                ExtraPurchaseRepository extraPurchaseRepository) {
        this.groupRepository = groupRepository;
        this.planRepository = planRepository;
        this.meteringRepository = meteringRepository;
        this.settlementRepository = settlementRepository;
        this.extraPurchaseRepository = extraPurchaseRepository;
    }

    /**
     * 登记临时加购并按当前欠供比例分配。同 requestId 同参重放返回首次结果，同键异参返回 409。
     */
    @Transactional
    public ExtraPurchaseView purchase(ExtraPurchaseRequest request) {
        var existing = extraPurchaseRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            ExtraPurchaseRepository.ExtraPurchaseRow row = existing.get();
            if (row.groupId() != request.groupId() || row.roundNo() != request.roundNo()
                    || row.amountM3() != request.amountM3()) {
                throw BizException.conflict("相同 requestId 的加购参数不一致，已拒绝");
            }
            return toView(row, true);
        }

        groupRepository.findGroupById(request.groupId())
                .orElseThrow(() -> BizException.notFound("轮灌组不存在: " + request.groupId()));
        if (settlementRepository.findByGroupAndRound(request.groupId(), request.roundNo()).isPresent()) {
            throw BizException.conflict("轮次已封存，禁止临时加购: round=" + request.roundNo());
        }
        Long planVersionId = planRepository
                .findEffectivePlanVersionId(request.groupId(), request.roundNo())
                .orElseThrow(() -> BizException.conflict(
                        "该轮次无生效计划，无法加购: group=" + request.groupId()
                                + ", round=" + request.roundNo()));

        List<PlanRepository.PlanDetailRow> planDetails = planRepository.findDetailsByVersionId(planVersionId);
        Map<Long, Long> metered = meteringRepository.findByGroupAndRound(request.groupId(), request.roundNo());
        List<AllocationCalculator.DeficitOutlet> deficits = planDetails.stream()
                .map(d -> new AllocationCalculator.DeficitOutlet(d.outletId(), d.outletCode(),
                        Math.max(0L, d.plannedM3() - metered.getOrDefault(d.outletId(), 0L))))
                .toList();

        List<AllocationCalculator.Allocation> allocations;
        try {
            allocations = AllocationCalculator.allocateByDeficit(request.amountM3(), deficits);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest(e.getMessage());
        }

        long purchaseId;
        try {
            purchaseId = extraPurchaseRepository.insert(request.groupId(), request.roundNo(),
                    request.requestId(), request.amountM3());
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("相同 requestId 的加购请求正在处理，请重试");
        }
        for (AllocationCalculator.Allocation allocation : allocations) {
            extraPurchaseRepository.insertDetail(purchaseId, allocation.outletId(), allocation.amountM3());
        }
        return toView(extraPurchaseRepository.findByRequestId(request.requestId()).orElseThrow(), false);
    }

    private ExtraPurchaseView toView(ExtraPurchaseRepository.ExtraPurchaseRow row, boolean replayed) {
        Map<Long, String> outletCodes = new java.util.HashMap<>();
        groupRepository.findOutletsByGroupId(row.groupId())
                .forEach(o -> outletCodes.put(o.id(), o.code()));
        List<ExtraAllocationView> allocations = extraPurchaseRepository.findDetails(row.id()).stream()
                .map(d -> new ExtraAllocationView(d.outletId(),
                        outletCodes.getOrDefault(d.outletId(), ""), d.amountM3()))
                .sorted((a, b) -> a.outletCode().compareTo(b.outletCode()))
                .toList();
        return new ExtraPurchaseView(row.id(), row.groupId(), row.roundNo(), row.amountM3(),
                replayed, allocations);
    }
}
