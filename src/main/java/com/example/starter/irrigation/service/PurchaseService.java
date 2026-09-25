package com.example.starter.irrigation.service;

import com.example.starter.irrigation.api.PurchaseDtos.ExtraAllocationLine;
import com.example.starter.irrigation.api.PurchaseDtos.ExtraPurchaseRequest;
import com.example.starter.irrigation.api.PurchaseDtos.ExtraPurchaseResponse;
import com.example.starter.irrigation.repo.GroupRepository.MemberRow;
import com.example.starter.irrigation.repo.MeteringRepository;
import com.example.starter.irrigation.repo.MeteringRepository.MeterRow;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.repo.PlanRepository.PlanLineRow;
import com.example.starter.irrigation.repo.PlanRepository.PlanVersionRow;
import com.example.starter.irrigation.repo.PurchaseRepository;
import com.example.starter.irrigation.repo.PurchaseRepository.AllocationRow;
import com.example.starter.irrigation.repo.PurchaseRepository.PurchaseRow;
import com.example.starter.irrigation.repo.SettlementRepository;
import com.example.starter.irrigation.support.ShortageAllocator;
import com.example.starter.irrigation.support.ShortageAllocator.Allocation;
import com.example.starter.irrigation.support.ShortageAllocator.OutletShortage;
import com.example.starter.irrigation.support.TurnClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 临时加购水量服务。
 *
 * <p>规则：加购水量只分配给当前仍欠供的斗口（计划量大于已确认实供），按欠供比例分配、
 * 取整到立方米、合计恰为加购量与欠供总额的较小值；获配水量视同补供落账。
 * 已封存轮次拒绝加购（封存为终态）；requestId 重放返回首次结果。
 */
@Service
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final PlanRepository planRepository;
    private final MeteringRepository meteringRepository;
    private final SettlementRepository settlementRepository;
    private final com.example.starter.irrigation.repo.GroupRepository groupRepository;
    private final TurnClock turnClock;
    private final Clock clock;

    public PurchaseService(PurchaseRepository purchaseRepository,
                           PlanRepository planRepository,
                           MeteringRepository meteringRepository,
                           SettlementRepository settlementRepository,
                           com.example.starter.irrigation.repo.GroupRepository groupRepository,
                           TurnClock turnClock, Clock clock) {
        this.purchaseRepository = purchaseRepository;
        this.planRepository = planRepository;
        this.meteringRepository = meteringRepository;
        this.settlementRepository = settlementRepository;
        this.groupRepository = groupRepository;
        this.turnClock = turnClock;
        this.clock = clock;
    }

    /** 提交加购并完成按欠供比例分配。 */
    @Transactional
    public ExtraPurchaseResponse purchase(ExtraPurchaseRequest request) {
        long turnNo = request.turnNo() != null ? request.turnNo() : turnClock.currentTurnNo();
        List<MemberRow> members = groupRepository.findMembers(request.groupCode());
        if (members.isEmpty()) {
            throw new NotFoundException("轮灌组不存在或无斗口成员: " + request.groupCode());
        }

        // 组行锁：与计量、封存串行，保证按同一时点欠供快照分配。
        meteringRepository.lockGroup(request.groupCode());

        // 锁内幂等复查：同键（无论是否与封存并发）均返回首次结果。
        PurchaseRow existing = purchaseRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.groupCode().equals(request.groupCode()) || existing.turnNo() != turnNo
                    || existing.amountCubicMeter() != request.amountCubicMeter()) {
                throw new ConflictException("加购 requestId 已用于不同参数: " + request.requestId());
            }
            return toResponse(existing);
        }

        // 锁内封存检查：若封存事务先提交，则加购必须失败，不得改动终态快照。
        if (settlementRepository.find(turnNo, request.groupCode()).isPresent()) {
            throw new BusinessRuleException("该轮该组已封存，不能再加购: turnNo=" + turnNo);
        }

        PlanVersionRow plan = planRepository.findEffective(turnNo, request.groupCode())
                .orElseThrow(() -> new BusinessRuleException(
                        "该轮该组无生效计划，无法计算欠供: turnNo=" + turnNo));
        List<PlanLineRow> lines = planRepository.findLines(plan.id());
        Map<String, MeterRow> meters = meteringRepository
                .findByTurnAndGroup(turnNo, request.groupCode()).stream()
                .collect(Collectors.toMap(MeterRow::outletCode, Function.identity()));

        List<OutletShortage> shortages = lines.stream()
                .map(line -> {
                    long delivered = meters.getOrDefault(line.outletCode(),
                            new MeterRow(turnNo, request.groupCode(), line.outletCode(), 0, 0,
                                    Instant.EPOCH)).deliveredCubicMeter();
                    // 欠供相对本轮总目标：按权重计划量 + 上轮结转补供量。
                    long target = line.planCubicMeter() + line.carryoverCubicMeter();
                    long shortage = Math.max(0, target - delivered);
                    return new OutletShortage(line.outletCode(), shortage);
                })
                .filter(shortage -> shortage.shortageCubicMeter() > 0)
                .toList();
        if (shortages.isEmpty()) {
            throw new BusinessRuleException("当前无欠供斗口，加购水量无法分配");
        }

        Instant now = clock.instant();
        long purchaseId = purchaseRepository.insertPurchase(request.requestId(),
                request.groupCode(), turnNo, request.amountCubicMeter(), now);
        List<Allocation> allocations =
                ShortageAllocator.allocateByShortage(shortages, request.amountCubicMeter());
        for (Allocation allocation : allocations) {
            purchaseRepository.insertAllocation(purchaseId, new AllocationRow(
                    allocation.outletCode(), allocation.shortageCubicMeter(),
                    allocation.allocatedCubicMeter()));
            if (allocation.allocatedCubicMeter() > 0) {
                meteringRepository.addSupplementalDelivery(turnNo, request.groupCode(),
                        allocation.outletCode(), allocation.allocatedCubicMeter(), now);
            }
        }

        return toResponse(purchaseRepository.findById(purchaseId).orElseThrow());
    }

    private ExtraPurchaseResponse toResponse(PurchaseRow purchase) {
        List<AllocationRow> rows = purchaseRepository.findAllocations(purchase.id());
        long allocatedSum = rows.stream().mapToLong(AllocationRow::allocatedCubicMeter).sum();
        List<ExtraAllocationLine> lines = rows.stream()
                .map(row -> new ExtraAllocationLine(row.outletCode(),
                        row.shortageCubicMeter(), row.allocatedCubicMeter()))
                .toList();
        return new ExtraPurchaseResponse(purchase.id(), purchase.requestId(), purchase.groupCode(),
                purchase.turnNo(), purchase.amountCubicMeter(), allocatedSum,
                purchase.createdAt(), lines);
    }
}
