package com.example.starter.irrigation.service;

import com.example.starter.irrigation.dto.PlanContracts.PlanDetailView;
import com.example.starter.irrigation.dto.PlanContracts.PlanView;
import com.example.starter.irrigation.dto.PlanContracts.PublishPlanRequest;
import com.example.starter.irrigation.error.BizException;
import com.example.starter.irrigation.repo.GroupRepository;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.repo.SettlementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 配水计划发布服务：按面积权重分配、requestId 幂等、版本冻结与同轮同组唯一生效。
 */
@Service
public class PlanService {

    /** 版本状态：生效。 */
    public static final String STATUS_EFFECTIVE = "EFFECTIVE";
    /** 版本状态：已废止（被新版本替代）。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** 每轮周期天数（UTC 日界）。 */
    public static final long ROUND_DAYS = 7L;

    private final GroupRepository groupRepository;
    private final PlanRepository planRepository;
    private final SettlementRepository settlementRepository;
    private final TransactionTemplate transactionTemplate;

    public PlanService(GroupRepository groupRepository,
                       PlanRepository planRepository,
                       SettlementRepository settlementRepository,
                       TransactionTemplate transactionTemplate) {
        this.groupRepository = groupRepository;
        this.planRepository = planRepository;
        this.settlementRepository = settlementRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 发布配水计划。同 requestId 同参重放返回首次结果，同键异参返回 409 且不产生新版本；
     * 同轮同组仅一个生效版本，并发发布唯一成功，失败者收到 409 且其版本随事务回滚。
     */
    public PlanView publish(PublishPlanRequest request) {
        Prepared prepared = prepare(request);
        var existing = planRepository.findVersionByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), prepared.fingerprint());
        }
        try {
            return transactionTemplate.execute(status -> doPublish(request, prepared));
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束兜底。胜者已提交，按指纹决定幂等重放或参数冲突。
            var now = planRepository.findVersionByRequestId(request.requestId());
            if (now.isPresent()) {
                return replayOrConflict(now.get(), prepared.fingerprint());
            }
            throw BizException.conflict("并发发布冲突：同轮同组仅允许一个生效版本");
        }
    }

    /**
     * 查询同轮同组全部版本（旧版本可查）。
     */
    public List<PlanView> listVersions(long groupId, int roundNo) {
        return planRepository.findVersionsByGroupAndRound(groupId, roundNo).stream()
                .map(row -> toView(row, false))
                .toList();
    }

    /**
     * 查询当前生效版本。
     */
    public PlanView getEffective(long groupId, int roundNo) {
        Long versionId = planRepository.findEffectivePlanVersionId(groupId, roundNo)
                .orElseThrow(() -> BizException.notFound(
                        "该轮次无生效计划: group=" + groupId + ", round=" + roundNo));
        return toView(planRepository.findVersionById(versionId).orElseThrow(), false);
    }

    /**
     * 轮次开始日（UTC，含）：每轮 7 天，自 1970-01-01 起算。
     */
    public static LocalDate roundStart(int roundNo) {
        return LocalDate.ofEpochDay(roundNo * ROUND_DAYS);
    }

    /**
     * 轮次结束日（UTC，不含）。
     */
    public static LocalDate roundEnd(int roundNo) {
        return roundStart(roundNo).plusDays(ROUND_DAYS);
    }

    /**
     * 预计算分配明细与幂等指纹（只读，不在发布事务内）。
     */
    private Prepared prepare(PublishPlanRequest request) {
        groupRepository.findGroupById(request.groupId())
                .orElseThrow(() -> BizException.notFound("轮灌组不存在: " + request.groupId()));
        List<GroupRepository.OutletRow> outlets = groupRepository.findOutletsByGroupId(request.groupId());
        if (outlets.isEmpty()) {
            throw BizException.badRequest("轮灌组没有斗口，无法发布计划: " + request.groupId());
        }

        // 上一轮已封存的欠供结转转为本轮补供。
        Map<Long, Long> carryoverIn = request.roundNo() > 0
                ? settlementRepository.findCarryoverByGroupAndRound(request.groupId(), request.roundNo() - 1)
                : Map.of();

        List<AllocationCalculator.WeightedOutlet> weighted = outlets.stream()
                .map(o -> new AllocationCalculator.WeightedOutlet(o.id(), o.code(), o.areaMu()))
                .toList();
        List<AllocationCalculator.Allocation> baseAllocations =
                AllocationCalculator.allocateByArea(request.totalWaterM3(), weighted);

        List<PlanDetailView> details = baseAllocations.stream()
                .map(a -> {
                    long carry = carryoverIn.getOrDefault(a.outletId(), 0L);
                    return new PlanDetailView(a.outletId(), a.outletCode(), a.amountM3(), carry,
                            a.amountM3() + carry);
                })
                .toList();
        return new Prepared(details, fingerprint(request, details));
    }

    private PlanView doPublish(PublishPlanRequest request, Prepared prepared) {
        if (settlementRepository.findByGroupAndRound(request.groupId(), request.roundNo()).isPresent()) {
            throw BizException.conflict("轮次已封存，禁止发布新计划: round=" + request.roundNo());
        }
        int versionNo = planRepository.findMaxVersionNo(request.groupId(), request.roundNo()) + 1;
        long versionId = planRepository.insertVersion(request.groupId(), request.roundNo(), versionNo,
                request.totalWaterM3(), request.requestId(), prepared.fingerprint(), STATUS_EFFECTIVE);
        for (PlanDetailView detail : prepared.details()) {
            planRepository.insertDetail(versionId, detail.outletId(), detail.outletCode(),
                    detail.baseM3(), detail.carryoverInM3(), detail.plannedM3());
        }

        var pointer = planRepository.findEffectivePlanVersionId(request.groupId(), request.roundNo());
        if (pointer.isEmpty()) {
            planRepository.insertEffectivePointer(request.groupId(), request.roundNo(), versionId);
        } else {
            int updated = planRepository.updateEffectivePointer(
                    request.groupId(), request.roundNo(), versionId, pointer.get());
            if (updated == 0) {
                throw BizException.conflict("并发发布冲突：同轮同组仅允许一个生效版本");
            }
            planRepository.updateVersionStatus(pointer.get(), STATUS_SUPERSEDED);
        }
        return toView(planRepository.findVersionById(versionId).orElseThrow(), false);
    }

    private PlanView replayOrConflict(PlanRepository.PlanVersionRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("相同 requestId 的计划参数不一致，已拒绝且不产生新版本");
        }
        return toView(row, true);
    }

    private PlanView toView(PlanRepository.PlanVersionRow row, boolean replayed) {
        List<PlanDetailView> details = planRepository.findDetailsByVersionId(row.id()).stream()
                .map(d -> new PlanDetailView(d.outletId(), d.outletCode(), d.baseM3(),
                        d.carryoverInM3(), d.plannedM3()))
                .toList();
        return new PlanView(row.id(), row.groupId(), row.roundNo(), row.versionNo(), row.status(),
                row.totalWaterM3(), roundStart(row.roundNo()), roundEnd(row.roundNo()), replayed, details);
    }

    /**
     * 幂等指纹：覆盖 requestId、轮灌组、轮次与组内全部斗口计划明细（按斗口编号排序规范化）。
     */
    static String fingerprint(PublishPlanRequest request, List<PlanDetailView> details) {
        StringBuilder canonical = new StringBuilder()
                .append(request.requestId()).append('|')
                .append(request.groupId()).append('|')
                .append(request.roundNo());
        details.stream()
                .sorted((a, b) -> a.outletCode().compareTo(b.outletCode()))
                .forEach(d -> canonical.append('|')
                        .append(d.outletCode()).append(':')
                        .append(d.baseM3()).append(':')
                        .append(d.carryoverInM3()).append(':')
                        .append(d.plannedM3()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 发布预计算结果。
     *
     * @param details     斗口计划明细（按斗口编号排序）
     * @param fingerprint 幂等指纹
     */
    private record Prepared(List<PlanDetailView> details, String fingerprint) {
    }
}
