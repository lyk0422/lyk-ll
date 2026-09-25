package com.example.starter.irrigation.service;

import com.example.starter.irrigation.api.PlanDtos.PlanLineResponse;
import com.example.starter.irrigation.api.PlanDtos.PlanVersionResponse;
import com.example.starter.irrigation.api.PlanDtos.PublishPlanRequest;
import com.example.starter.irrigation.repo.GroupRepository.MemberRow;
import com.example.starter.irrigation.repo.MeteringRepository;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.repo.PlanRepository.PlanLineRow;
import com.example.starter.irrigation.repo.PlanRepository.PlanVersionRow;
import com.example.starter.irrigation.repo.SettlementRepository;
import com.example.starter.irrigation.support.AllocationCalculator;
import com.example.starter.irrigation.support.AllocationCalculator.Allocation;
import com.example.starter.irrigation.support.AllocationCalculator.OutletArea;
import com.example.starter.irrigation.support.Fingerprint;
import com.example.starter.irrigation.support.TurnClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配水计划发布服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>按组内斗口灌溉面积权重分配可供水量，四位小数权重、整立方米分配、差额归面积最大斗口；</li>
 *   <li>requestId 幂等：同键同参返回首次结果，同键异参 409 且不产生新版本；</li>
 *   <li>计划发布后冻结；修改同轮同组计划须发新版本，旧版本置 SUPERSEDED 且可查；</li>
 *   <li>同轮同组仅一个 EFFECTIVE 版本，组行锁 + 生效唯一键索引保证并发发布唯一成功。</li>
 * </ul>
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final com.example.starter.irrigation.repo.GroupRepository groupRepository;
    private final MeteringRepository meteringRepository;
    private final SettlementRepository settlementRepository;
    private final TurnClock turnClock;
    private final Clock clock;

    public PlanService(PlanRepository planRepository,
                       com.example.starter.irrigation.repo.GroupRepository groupRepository,
                       MeteringRepository meteringRepository,
                       SettlementRepository settlementRepository,
                       TurnClock turnClock, Clock clock) {
        this.planRepository = planRepository;
        this.groupRepository = groupRepository;
        this.meteringRepository = meteringRepository;
        this.settlementRepository = settlementRepository;
        this.turnClock = turnClock;
        this.clock = clock;
    }

    /** 发布计划（或新版本）。 */
    @Transactional
    public PlanVersionResponse publish(PublishPlanRequest request) {
        long turnNo = request.turnNo() != null ? request.turnNo() : turnClock.currentTurnNo();
        List<MemberRow> members = requireGroupMembers(request.groupCode());

        // 先按本次参数试算指纹：与 requestId 首次发布指纹比对。
        List<Allocation> allocations = AllocationCalculator.allocateByArea(
                members.stream()
                        .map(member -> new OutletArea(member.outletCode(), member.areaSquareMeter()))
                        .toList(),
                request.availableCubicMeter());

        // 组行锁串行化同组发布与计量事务。
        meteringRepository.lockGroup(request.groupCode());

        // 承接上一轮封存结转：上轮（turnNo-1）已封存时，其结转补供量计入本轮明细；
        // 锁在封存事务之后获取，所见上轮结转必为终态。
        java.util.Map<String, Long> priorCarryover = settlementRepository
                .findCarryovers(turnNo - 1L, request.groupCode()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.example.starter.irrigation.repo.SettlementRepository.CarryoverRow::outletCode,
                        com.example.starter.irrigation.repo.SettlementRepository.CarryoverRow::carryoverCubicMeter));

        String candidateFingerprint = Fingerprint.ofPlan(request.requestId(), turnNo, request.groupCode(),
                allocations.stream()
                        .map(a -> new Fingerprint.PlanLine(a.outletCode(), a.areaSquareMeter(),
                                a.planCubicMeter(),
                                priorCarryover.getOrDefault(a.outletCode(), 0L)))
                        .toList());

        // 同键幂等优先：锁内复查，覆盖锁外检查间隙的并发提交。
        PlanVersionRow existing = planRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.fingerprint().equals(candidateFingerprint)) {
                throw new ConflictException(
                        "requestId 已用于不同参数的计划发布: " + request.requestId());
            }
            return toResponse(existing, planRepository.findLines(existing.id()));
        }

        // 封存终态：非幂等重放的新发布/修订不得再改动已封存轮次。
        if (settlementRepository.find(turnNo, request.groupCode()).isPresent()) {
            throw new BusinessRuleException("该轮该组已封存，计划不可再变更: turnNo=" + turnNo
                    + ", group=" + request.groupCode());
        }

        PlanVersionRow currentEffective = planRepository.findEffective(turnNo, request.groupCode())
                .orElse(null);
        int versionNo;
        if (currentEffective == null) {
            // 首次发布：版本号恒为 1，生效唯一索引对并发首次发布做最终仲裁。
            versionNo = 1;
        } else if (request.baseVersionNo() != null) {
            // 显式修订：基线必须仍是当前生效版本，否则属并发修订冲突。
            if (request.baseVersionNo() != currentEffective.versionNo()) {
                throw new ConflictException(
                        "修订基线版本已不是当前生效版本，请基于最新版本重新发布: turnNo=" + turnNo
                                + ", group=" + request.groupCode()
                                + ", baseVersionNo=" + request.baseVersionNo()
                                + ", currentVersionNo=" + currentEffective.versionNo());
            }
            planRepository.supersedeEffective(turnNo, request.groupCode());
            versionNo = planRepository.nextVersionNo(turnNo, request.groupCode());
        } else {
            // 已有生效版本且未提供修订基线：可能是重复发布或并发发布，一律不产生新版本。
            throw new ConflictException(
                    "同轮同组已有生效计划，修改请携带当前生效版本号 baseVersionNo 发布新版本: turnNo="
                            + turnNo + ", group=" + request.groupCode()
                            + ", currentVersionNo=" + currentEffective.versionNo());
        }

        Instant now = clock.instant();
        PlanVersionRow row = new PlanVersionRow(0L, request.groupCode(), turnNo, versionNo,
                "EFFECTIVE", request.availableCubicMeter(), request.requestId(),
                candidateFingerprint, now);
        long planId;
        try {
            planId = planRepository.insertEffectiveVersion(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发首次发布仲裁兜底：生效唯一键已被先提交事务占用，本次失败不产生新版本。
            throw new ConflictException(
                    "同轮同组并发发布冲突，唯一生效版本已产生: turnNo=" + turnNo
                            + ", group=" + request.groupCode());
        }
        List<PlanLineRow> lines = allocations.stream()
                .map(a -> new PlanLineRow(a.outletCode(), a.areaSquareMeter(), a.weight(),
                        a.planCubicMeter(), priorCarryover.getOrDefault(a.outletCode(), 0L)))
                .toList();
        planRepository.insertLines(planId, lines);

        return toResponse(new PlanVersionRow(planId, row.groupCode(), row.turnNo(), row.versionNo(),
                row.status(), row.availableCubicMeter(), row.requestId(), row.fingerprint(),
                row.publishedAt()), lines);
    }

    /** 查同轮同组当前生效版本；不存在返回 404。 */
    public PlanVersionResponse getEffective(long turnNo, String groupCode) {
        requireGroupMembers(groupCode);
        PlanVersionRow row = planRepository.findEffective(turnNo, groupCode)
                .orElseThrow(() -> new NotFoundException(
                        "该轮该组无生效计划: turnNo=" + turnNo + ", group=" + groupCode));
        return toResponse(row, planRepository.findLines(row.id()));
    }

    /** 查指定版本（含已取代的旧版本）；不存在返回 404。 */
    public PlanVersionResponse getVersion(long turnNo, String groupCode, int versionNo) {
        requireGroupMembers(groupCode);
        PlanVersionRow row = planRepository.findVersion(turnNo, groupCode, versionNo)
                .orElseThrow(() -> new NotFoundException(
                        "计划版本不存在: turnNo=" + turnNo + ", group=" + groupCode
                                + ", versionNo=" + versionNo));
        return toResponse(row, planRepository.findLines(row.id()));
    }

    /** 列同轮同组全部版本（旧版本可查）。 */
    public List<PlanVersionResponse> listVersions(long turnNo, String groupCode) {
        requireGroupMembers(groupCode);
        return planRepository.listVersions(turnNo, groupCode).stream()
                .map(row -> toResponse(row, planRepository.findLines(row.id())))
                .toList();
    }

    private List<MemberRow> requireGroupMembers(String groupCode) {
        List<MemberRow> members = groupRepository.findMembers(groupCode);
        if (members.isEmpty()) {
            throw new NotFoundException("轮灌组不存在或无斗口成员: " + groupCode);
        }
        return members;
    }

    private PlanVersionResponse toResponse(PlanVersionRow row, List<PlanLineRow> lines) {
        List<PlanLineResponse> lineResponses = lines.stream()
                .map(line -> new PlanLineResponse(line.outletCode(), line.areaSquareMeter(),
                        line.weight(), line.planCubicMeter(), line.carryoverCubicMeter()))
                .toList();
        return new PlanVersionResponse(row.id(), row.groupCode(), row.turnNo(), row.versionNo(),
                row.status(), row.availableCubicMeter(), row.requestId(), row.fingerprint(),
                row.publishedAt(), lineResponses);
    }
}
