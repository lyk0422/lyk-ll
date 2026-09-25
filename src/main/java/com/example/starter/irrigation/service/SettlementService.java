package com.example.starter.irrigation.service;

import com.example.starter.irrigation.api.SettlementDtos.CarryoverLine;
import com.example.starter.irrigation.api.SettlementDtos.SealRequest;
import com.example.starter.irrigation.api.SettlementDtos.SettlementResponse;
import com.example.starter.irrigation.repo.MeteringRepository;
import com.example.starter.irrigation.repo.MeteringRepository.MeterRow;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.repo.PlanRepository.PlanLineRow;
import com.example.starter.irrigation.repo.PlanRepository.PlanVersionRow;
import com.example.starter.irrigation.repo.SettlementRepository;
import com.example.starter.irrigation.repo.SettlementRepository.CarryoverRow;
import com.example.starter.irrigation.repo.SettlementRepository.SettlementRow;
import com.example.starter.irrigation.support.AllocationCalculator;
import com.example.starter.irrigation.support.TurnClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轮次结束欠供结转封存服务。
 *
 * <p>封存事务先取组行锁，因此所见计量快照即该轮最后一次已确认计量，结转结果稳定可复算。
 * 欠供 {@code max(计划-实供,0)} 结转上限为本轮计划量 30%（恰满允许），超出部分作废。
 * 封存为终态：重复封存幂等返回首次结果；封存后迟到计量由计量服务登记为迟到事件。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final PlanRepository planRepository;
    private final MeteringRepository meteringRepository;
    private final com.example.starter.irrigation.repo.GroupRepository groupRepository;
    private final TurnClock turnClock;
    private final Clock clock;

    public SettlementService(SettlementRepository settlementRepository,
                             PlanRepository planRepository,
                             MeteringRepository meteringRepository,
                             com.example.starter.irrigation.repo.GroupRepository groupRepository,
                             TurnClock turnClock, Clock clock) {
        this.settlementRepository = settlementRepository;
        this.planRepository = planRepository;
        this.meteringRepository = meteringRepository;
        this.groupRepository = groupRepository;
        this.turnClock = turnClock;
        this.clock = clock;
    }

    /** 封存指定轮次指定组；重复封存幂等成功并返回首次结果。 */
    @Transactional
    public SettlementResponse seal(SealRequest request) {
        long turnNo = request.turnNo() != null ? request.turnNo() : turnClock.currentTurnNo();
        if (groupRepository.findMembers(request.groupCode()).isEmpty()) {
            throw new NotFoundException("轮灌组不存在或无斗口成员: " + request.groupCode());
        }

        // 快速幂等路径：已封存直接返回首次结果。
        SettlementRow existing = settlementRepository.find(turnNo, request.groupCode()).orElse(null);
        if (existing != null) {
            return toResponse(existing, true);
        }

        // 组行锁：等待所有在途计量事务提交，确保以最后一次已确认计量快照结算。
        meteringRepository.lockGroup(request.groupCode());

        existing = settlementRepository.find(turnNo, request.groupCode()).orElse(null);
        if (existing != null) {
            return toResponse(existing, true);
        }

        PlanVersionRow plan = planRepository.findEffective(turnNo, request.groupCode())
                .orElseThrow(() -> new BusinessRuleException(
                        "该轮该组无生效计划，无法封存: turnNo=" + turnNo));
        List<PlanLineRow> lines = planRepository.findLines(plan.id());
        Map<String, MeterRow> meters = meteringRepository
                .findByTurnAndGroup(turnNo, request.groupCode()).stream()
                .collect(Collectors.toMap(MeterRow::outletCode, meter -> meter));

        Instant now = clock.instant();
        try {
            settlementRepository.insertSettlement(turnNo, request.groupCode(), now);
        } catch (DuplicateKeyException e) {
            SettlementRow winner = settlementRepository.find(turnNo, request.groupCode())
                    .orElseThrow(() -> e);
            return toResponse(winner, true);
        }

        for (PlanLineRow line : lines) {
            long delivered = meters.getOrDefault(line.outletCode(),
                    new MeterRow(turnNo, request.groupCode(), line.outletCode(), 0, 0, now))
                    .deliveredCubicMeter();
            // 本轮总供水目标 = 按权重计划量 + 上轮结转补供量；欠供按总目标计。
            long target = line.planCubicMeter() + line.carryoverCubicMeter();
            long shortage = Math.max(0, target - delivered);
            // 30% 上限以本轮按权重计划量为基（恰满允许），超出部分作废。
            long carryoverCap = AllocationCalculator.carryover(line.planCubicMeter(), shortage);
            long voided = shortage - carryoverCap;
            settlementRepository.insertCarryover(turnNo, request.groupCode(), new CarryoverRow(
                    line.outletCode(), line.planCubicMeter(), delivered, shortage,
                    carryoverCap, voided, now));
        }

        return toResponse(new SettlementRow(turnNo, request.groupCode(), "SEALED", now), false);
    }

    /** 查询封存结果；未封存返回 404。 */
    public SettlementResponse getSettlement(long turnNo, String groupCode) {
        SettlementRow row = settlementRepository.find(turnNo, groupCode)
                .orElseThrow(() -> new NotFoundException(
                        "该轮该组尚未封存: turnNo=" + turnNo + ", group=" + groupCode));
        return toResponse(row, false);
    }

    private SettlementResponse toResponse(SettlementRow row, boolean idempotentReplay) {
        List<CarryoverLine> lines = settlementRepository
                .findCarryovers(row.turnNo(), row.groupCode()).stream()
                .map(carryover -> new CarryoverLine(carryover.outletCode(),
                        carryover.planCubicMeter(), carryover.deliveredCubicMeter(),
                        carryover.shortageCubicMeter(), carryover.carryoverCubicMeter(),
                        carryover.voidedCubicMeter()))
                .toList();
        return new SettlementResponse(row.turnNo(), row.groupCode(), row.status(),
                idempotentReplay, row.sealedAt(), lines);
    }
}
