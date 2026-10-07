package com.example.starter.service;

import com.example.starter.domain.AdjustmentGroup;
import com.example.starter.domain.AdjustmentGroupMember;
import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.CoupledAdjustment;
import com.example.starter.domain.CoupledAdjustmentItem;
import com.example.starter.domain.HeatingUnit;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.Valve;
import com.example.starter.repository.AdjustmentGroupMemberRepository;
import com.example.starter.repository.AdjustmentGroupRepository;
import com.example.starter.repository.AdjustmentTaskRepository;
import com.example.starter.repository.CoupledAdjustmentRepository;
import com.example.starter.repository.StationRepository;
import com.example.starter.repository.UnitRepository;
import com.example.starter.repository.UnsealOrderRepository;
import com.example.starter.repository.UnsealQuotaUsageRepository;
import com.example.starter.repository.ValveRepository;
import com.example.starter.repository.ValveSealRepository;
import com.example.starter.repository.ValveVersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联调服务：同站 2~5 只阀的耦合联调组建制、原子联调提交与解除。
 * 锁顺序约定：station → group → valve（按 ID 升序）→ unseal（按 ID 升序），
 * 与单阀调节、铅封登记/复封共享阀门行锁与开度版本，竞争按共享阀版本提交裁决。
 */
@Service
public class CoupledAdjustmentService {

    /** 联调组成员下限：至少 2 只不同阀。 */
    public static final int MIN_MEMBERS = 2;
    /** 联调组成员上限：至多 5 只不同阀。 */
    public static final int MAX_MEMBERS = 5;
    /** 单阀权重下限（正整数）。 */
    public static final int MIN_WEIGHT = 1;
    /** 单阀权重上限。 */
    public static final int MAX_WEIGHT = 10;
    /** 单阀本次变化上限：不超过 10 个百分点（恰 10 合法）。 */
    public static final int MAX_STEP_POINTS = 10;

    private final StationRepository stationRepository;
    private final UnitRepository unitRepository;
    private final ValveRepository valveRepository;
    private final ValveVersionRepository versionRepository;
    private final AdjustmentTaskRepository taskRepository;
    private final ValveSealRepository sealRepository;
    private final UnsealOrderRepository unsealRepository;
    private final UnsealQuotaUsageRepository usageRepository;
    private final AdjustmentGroupRepository groupRepository;
    private final AdjustmentGroupMemberRepository memberRepository;
    private final CoupledAdjustmentRepository coupledRepository;
    private final Clock clock;

    public CoupledAdjustmentService(StationRepository stationRepository,
                                    UnitRepository unitRepository,
                                    ValveRepository valveRepository,
                                    ValveVersionRepository versionRepository,
                                    AdjustmentTaskRepository taskRepository,
                                    ValveSealRepository sealRepository,
                                    UnsealOrderRepository unsealRepository,
                                    UnsealQuotaUsageRepository usageRepository,
                                    AdjustmentGroupRepository groupRepository,
                                    AdjustmentGroupMemberRepository memberRepository,
                                    CoupledAdjustmentRepository coupledRepository,
                                    Clock clock) {
        this.stationRepository = stationRepository;
        this.unitRepository = unitRepository;
        this.valveRepository = valveRepository;
        this.versionRepository = versionRepository;
        this.taskRepository = taskRepository;
        this.sealRepository = sealRepository;
        this.unsealRepository = unsealRepository;
        this.usageRepository = usageRepository;
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.coupledRepository = coupledRepository;
        this.clock = clock;
    }

    /**
     * 建组成员规格。
     *
     * @param valveId 成员阀门 ID
     * @param weight  阀门权重，正整数 1~10，建组时冻结
     */
    public record MemberSpec(long valveId, int weight) {
    }

    /**
     * 联调逐阀目标规格。
     *
     * @param valveId             成员阀门 ID
     * @param expectedValveVersion 逐阀当前开度版本（无版本为 0）
     * @param targetOpening       目标开度，0~100 整数百分点
     * @param unsealId            铅封阀提供的解封单 ID；未封阀为 null
     * @param unsealVersion       解封单乐观锁版本；未封阀为 null
     */
    public record TargetSpec(long valveId, int expectedValveVersion, int targetOpening,
                             Long unsealId, Integer unsealVersion) {
    }

    /**
     * 成员视图：冻结权重/版本与查询时当前开度/版本，可复算净加权开度。
     */
    public record MemberView(long valveId, int weight, int frozenValveVersion,
                             java.math.BigDecimal currentOpening, int currentValveVersion) {
    }

    /**
     * 联调组视图：组头与成员列表。
     */
    public record GroupView(AdjustmentGroup group, List<MemberView> members) {
    }

    /**
     * 联调记录视图：记录头与逐阀明细。
     */
    public record CoupledView(CoupledAdjustment coupled, List<CoupledAdjustmentItem> items) {
    }

    /**
     * 建立联调组：同站 2~5 只不同阀，冻结各阀权重 1~10 及当前开度版本，
     * 阀只能属于一个活动组；requestId 幂等，同参重放、异参 409。
     */
    @Transactional
    public GroupView createGroup(long stationId, String requestId, List<MemberSpec> members) {
        List<MemberSpec> sorted = sortedMembers(members);
        String fingerprint = groupFingerprint(requestId, stationId, sorted);
        var existing = groupRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayGroupOrConflict(existing.get(), fingerprint);
        }
        validateMemberSpecs(stationId, sorted);
        // 行级锁串行化同站建组与联调提交，保证“阀只属于一个活动组”的判定可串行。
        stationRepository.lockById(stationId)
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + stationId));
        var existingAfterLock = groupRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayGroupOrConflict(existingAfterLock.get(), fingerprint);
        }
        Instant now = clock.instant();
        Map<Long, Integer> frozenVersions = new HashMap<>();
        for (MemberSpec spec : sorted) {
            Valve valve = valveRepository.findById(spec.valveId())
                    .orElseThrow(() -> new NotFoundException(
                            "阀门不存在: valveId=" + spec.valveId()));
            HeatingUnit unit = unitRepository.findById(valve.unitId())
                    .orElseThrow(() -> new NotFoundException("单元不存在: unitId=" + valve.unitId()));
            if (unit.stationId() != stationId) {
                throw new NotFoundException("阀门不属于该换热站: valveId=" + spec.valveId()
                        + ", stationId=" + stationId);
            }
            if (memberRepository.existsActiveByValveId(spec.valveId())) {
                throw new UnprocessableException(
                        "阀门已属于其他活动联调组: valveId=" + spec.valveId());
            }
            frozenVersions.put(spec.valveId(), versionRepository.maxVersionNo(spec.valveId()));
        }
        AdjustmentGroup group = new AdjustmentGroup(0, stationId, AdjustmentGroup.GroupStatus.ACTIVE,
                1, now, null, requestId, fingerprint, null, null);
        long groupId;
        try {
            groupId = groupRepository.insert(group);
        } catch (DuplicateKeyException e) {
            // 并发下同键插入：唯一约束保证只建组一次，重放首个结果。
            return groupRepository.findByRequestId(requestId)
                    .map(g -> replayGroupOrConflict(g, fingerprint))
                    .orElseThrow(() -> e);
        }
        for (MemberSpec spec : sorted) {
            memberRepository.insert(groupId, spec.valveId(), spec.weight(),
                    frozenVersions.get(spec.valveId()), now);
        }
        return getGroup(groupId);
    }

    /**
     * 解除联调组：仅释放组归属，不回拨开度或返还已消费额度；
     * 重复同键解除重放首次结果，旧版 409。
     */
    @Transactional
    public AdjustmentGroup dissolveGroup(long groupId, String requestId, int expectedVersion) {
        String fingerprint = Fingerprints.of("AGROUP-DISSOLVE", requestId, String.valueOf(groupId),
                String.valueOf(expectedVersion));
        AdjustmentGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        if (group.status() == AdjustmentGroup.GroupStatus.DISSOLVED) {
            return replayDissolve(group, requestId, fingerprint);
        }
        long stationId = group.stationId();
        stationRepository.lockById(stationId)
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + stationId));
        group = groupRepository.lockById(groupId).orElseThrow();
        if (group.status() == AdjustmentGroup.GroupStatus.DISSOLVED) {
            return replayDissolve(group, requestId, fingerprint);
        }
        if (group.version() != expectedVersion) {
            throw new ConflictException("联调组版本不一致: 当前=" + group.version()
                    + ", expectedVersion=" + expectedVersion);
        }
        int updated = groupRepository.dissolve(groupId, expectedVersion, clock.instant(),
                requestId, fingerprint);
        if (updated != 1) {
            throw new ConflictException("联调组并发变更，解除失败: groupId=" + groupId);
        }
        return groupRepository.findById(groupId).orElseThrow();
    }

    /**
     * 联调提交：全组目标开度与逐阀当前版本一次提交。
     * 加权目标总和须精确等于加权调前开度总和；每阀变化不超过 10 个百分点且守 24 小时
     * 最多 3 次规则；铅封阀逐一提供合法解封单（半开窗口内、剩余额度充足）。
     * 任一校验失败全组零变化且不占幂等键；全部合法才原子生成联调记录、推进联调单版本、
     * 更新所有开度版本，并对每个实际变化阀消费一次额度和次数，不变阀不消费。
     */
    @Transactional
    public CoupledView submit(long groupId, String requestId, int expectedVersion,
                              Instant requestedAt, List<TargetSpec> targets) {
        AdjustmentGroup found = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        List<AdjustmentGroupMember> members = memberRepository.findByGroupId(groupId);
        String fingerprint = coupledFingerprint(requestId, groupId, expectedVersion,
                requestedAt, members, targets);
        var existing = coupledRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayCoupledOrConflict(existing.get(), fingerprint);
        }
        if (requestedAt == null) {
            throw new UnprocessableException("请求须携带 UTC 时刻 requestedAt");
        }
        // 锁顺序：station → group → valve（升序）→ unseal（升序）。
        stationRepository.lockById(found.stationId())
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + found.stationId()));
        AdjustmentGroup group = groupRepository.lockById(groupId).orElseThrow();
        var existingAfterLock = coupledRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayCoupledOrConflict(existingAfterLock.get(), fingerprint);
        }
        if (group.status() != AdjustmentGroup.GroupStatus.ACTIVE) {
            throw new UnprocessableException("联调组已解除，禁止联调: groupId=" + groupId);
        }
        if (group.version() != expectedVersion) {
            throw new ConflictException("联调组版本不一致: 当前=" + group.version()
                    + ", expectedVersion=" + expectedVersion);
        }
        Map<Long, AdjustmentGroupMember> memberByValve = new HashMap<>();
        for (AdjustmentGroupMember member : members) {
            memberByValve.put(member.valveId(), member);
        }
        Map<Long, TargetSpec> targetByValve = validateTargets(members, targets);
        // 持组锁后逐阀加行锁并核对共享开度版本：与单阀调节/复封竞争按版本裁决。
        Map<Long, Valve> valveById = new HashMap<>();
        Map<Long, Integer> currentVersionByValve = new HashMap<>();
        for (AdjustmentGroupMember member : members) {
            Valve valve = valveRepository.lockById(member.valveId()).orElseThrow();
            valveById.put(valve.id(), valve);
            int currentVersion = versionRepository.maxVersionNo(valve.id());
            currentVersionByValve.put(valve.id(), currentVersion);
            TargetSpec target = targetByValve.get(valve.id());
            if (currentVersion != target.expectedValveVersion()) {
                throw new UnprocessableException("阀门开度版本不一致（旧版本）: valveId=" + valve.id()
                        + ", 当前=" + currentVersion
                        + ", expectedValveVersion=" + target.expectedValveVersion());
            }
        }
        validateWeightedConservation(members, valveById, targetByValve);
        validateStepAndRange(valveById, targetByValve);
        Instant now = clock.instant();
        validateDailyQuota(members, valveById, targetByValve, now);
        Map<Long, UnsealOrder> unsealByValve = validateSealedValves(members, targetByValve,
                currentVersionByValve, now);
        // 全部校验通过：原子生成联调记录、推进联调单版本、更新全部开度版本并逐阀消费。
        CoupledAdjustment coupled = new CoupledAdjustment(0, groupId, group.stationId(),
                expectedVersion, requestedAt, requestId, fingerprint, now);
        long coupledId;
        try {
            coupledId = coupledRepository.insert(coupled);
        } catch (DuplicateKeyException e) {
            // 并发下同键插入：唯一约束保证只受理一次，重放首个结果且不重复消费。
            return coupledRepository.findByRequestId(requestId)
                    .map(c -> replayCoupledOrConflict(c, fingerprint))
                    .orElseThrow(() -> e);
        }
        int bumped = groupRepository.bumpVersion(groupId, expectedVersion);
        if (bumped != 1) {
            throw new ConflictException("联调组并发变更，版本推进失败: groupId=" + groupId);
        }
        for (AdjustmentGroupMember member : members) {
            Valve valve = valveById.get(member.valveId());
            TargetSpec target = targetByValve.get(member.valveId());
            BigDecimal oldOpening = valve.openingPercent();
            BigDecimal newOpening = BigDecimal.valueOf(target.targetOpening());
            UnsealOrder order = unsealByValve.get(member.valveId());
            Long taskId = null;
            if (newOpening.compareTo(oldOpening) != 0) {
                // 实际变化阀：生成单阀调节任务（沿用原温差评估及归档），消费一次 24 小时次数。
                taskId = insertCoupledTask(group.stationId(), valve, requestId,
                        oldOpening, newOpening, now);
                valveRepository.updateOpening(valve.id(), newOpening, now);
                versionRepository.insert(valve.id(), taskId,
                        currentVersionByValve.get(valve.id()) + 1, oldOpening, newOpening, now);
                if (order != null) {
                    // 铅封阀：同事务消费一次解封额度。
                    int consumed = unsealRepository.consumeQuota(order.id(), order.version());
                    if (consumed != 1) {
                        throw new ConflictException("解封单并发变更，额度消费失败: unsealId=" + order.id());
                    }
                    usageRepository.insert(order.id(), taskId,
                            coupledTaskRequestId(requestId, valve.id()), now);
                }
            }
            coupledRepository.insertItem(coupledId, member.valveId(),
                    order == null ? null : order.id(), taskId, member.weight(),
                    oldOpening, newOpening, now);
        }
        return getCoupled(coupledId);
    }

    /**
     * 查询联调组详情：成员冻结权重/版本与当前开度/版本，可复算净加权开度及逐阀额度。
     */
    @Transactional(readOnly = true)
    public GroupView getGroup(long groupId) {
        AdjustmentGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        List<MemberView> views = new ArrayList<>();
        for (AdjustmentGroupMember member : memberRepository.findByGroupId(groupId)) {
            Valve valve = valveRepository.findById(member.valveId())
                    .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + member.valveId()));
            views.add(new MemberView(member.valveId(), member.weight(), member.frozenValveVersion(),
                    valve.openingPercent(), versionRepository.maxVersionNo(member.valveId())));
        }
        return new GroupView(group, views);
    }

    /**
     * 查询联调记录及逐阀明细（联调前后开度保留，可复算净加权开度及逐阀额度）。
     */
    @Transactional(readOnly = true)
    public CoupledView getCoupled(long coupledId) {
        CoupledAdjustment coupled = coupledRepository.findById(coupledId)
                .orElseThrow(() -> new NotFoundException("联调记录不存在: coupledId=" + coupledId));
        return new CoupledView(coupled, coupledRepository.findItemsByCoupledId(coupledId));
    }

    /**
     * 查询联调组的全部联调记录，按主键升序。
     */
    @Transactional(readOnly = true)
    public List<CoupledAdjustment> listCoupledByGroup(long groupId) {
        groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        return coupledRepository.findByGroupId(groupId);
    }

    private List<MemberSpec> sortedMembers(List<MemberSpec> members) {
        if (members == null) {
            return List.of();
        }
        return members.stream()
                .sorted(Comparator.comparingLong(MemberSpec::valveId))
                .toList();
    }

    private String groupFingerprint(String requestId, long stationId, List<MemberSpec> sorted) {
        List<String> parts = new ArrayList<>(List.of("AGROUP", requestId, String.valueOf(stationId)));
        for (MemberSpec spec : sorted) {
            parts.add(spec.valveId() + ":" + spec.weight());
        }
        return Fingerprints.of(parts.toArray(new String[0]));
    }

    private String coupledFingerprint(String requestId, long groupId, int expectedVersion,
                                      Instant requestedAt, List<AdjustmentGroupMember> members,
                                      List<TargetSpec> targets) {
        Map<Long, Integer> weightByValve = new HashMap<>();
        for (AdjustmentGroupMember member : members) {
            weightByValve.put(member.valveId(), member.weight());
        }
        List<TargetSpec> sorted = targets == null ? List.of() : targets.stream()
                .sorted(Comparator.comparingLong(TargetSpec::valveId))
                .toList();
        List<String> parts = new ArrayList<>(List.of("COUPLED", requestId, String.valueOf(groupId),
                String.valueOf(expectedVersion),
                requestedAt == null ? null : requestedAt.toString()));
        for (TargetSpec target : sorted) {
            parts.add(target.valveId() + ":" + weightByValve.getOrDefault(target.valveId(), -1)
                    + ":" + target.expectedValveVersion() + ":" + target.targetOpening()
                    + ":" + target.unsealId() + ":" + target.unsealVersion());
        }
        return Fingerprints.of(parts.toArray(new String[0]));
    }

    private void validateMemberSpecs(long stationId, List<MemberSpec> sorted) {
        if (sorted.size() < MIN_MEMBERS || sorted.size() > MAX_MEMBERS) {
            throw new UnprocessableException("联调组成员须为 2~5 只不同阀，当前: " + sorted.size());
        }
        Set<Long> seen = new HashSet<>();
        for (MemberSpec spec : sorted) {
            if (!seen.add(spec.valveId())) {
                throw new UnprocessableException("联调组成员阀门重复: valveId=" + spec.valveId());
            }
            if (spec.weight() < MIN_WEIGHT || spec.weight() > MAX_WEIGHT) {
                throw new UnprocessableException("阀门权重须为 1~10 正整数: valveId=" + spec.valveId()
                        + ", weight=" + spec.weight());
            }
        }
    }

    private Map<Long, TargetSpec> validateTargets(List<AdjustmentGroupMember> members,
                                                  List<TargetSpec> targets) {
        List<TargetSpec> sorted = targets == null ? List.of() : targets;
        Map<Long, TargetSpec> targetByValve = new HashMap<>();
        for (TargetSpec target : sorted) {
            if (targetByValve.put(target.valveId(), target) != null) {
                throw new UnprocessableException("联调目标阀门重复: valveId=" + target.valveId());
            }
        }
        for (AdjustmentGroupMember member : members) {
            if (!targetByValve.containsKey(member.valveId())) {
                throw new UnprocessableException("联调目标缺少成员阀，全组零变化: valveId=" + member.valveId());
            }
        }
        for (TargetSpec target : sorted) {
            boolean isMember = members.stream()
                    .anyMatch(m -> m.valveId() == target.valveId());
            if (!isMember) {
                throw new UnprocessableException("联调目标包含非成员阀: valveId=" + target.valveId());
            }
            if (target.targetOpening() < 0 || target.targetOpening() > 100) {
                throw new UnprocessableException("目标开度须为 0~100 整数百分点: valveId="
                        + target.valveId() + ", target=" + target.targetOpening());
            }
        }
        return targetByValve;
    }

    private void validateWeightedConservation(List<AdjustmentGroupMember> members,
                                              Map<Long, Valve> valveById,
                                              Map<Long, TargetSpec> targetByValve) {
        BigDecimal weightedTarget = BigDecimal.ZERO;
        BigDecimal weightedCurrent = BigDecimal.ZERO;
        for (AdjustmentGroupMember member : members) {
            BigDecimal weight = BigDecimal.valueOf(member.weight());
            weightedTarget = weightedTarget.add(weight.multiply(
                    BigDecimal.valueOf(targetByValve.get(member.valveId()).targetOpening())));
            weightedCurrent = weightedCurrent.add(weight.multiply(
                    valveById.get(member.valveId()).openingPercent()));
        }
        // 精确比较不舍入：加权目标总和须等于加权调前开度总和。
        if (weightedTarget.compareTo(weightedCurrent) != 0) {
            throw new UnprocessableException("加权目标总和须等于加权调前开度总和（精确比较）: 目标="
                    + weightedTarget.stripTrailingZeros().toPlainString()
                    + ", 调前=" + weightedCurrent.stripTrailingZeros().toPlainString());
        }
    }

    private void validateStepAndRange(Map<Long, Valve> valveById,
                                      Map<Long, TargetSpec> targetByValve) {
        for (Map.Entry<Long, TargetSpec> entry : targetByValve.entrySet()) {
            BigDecimal delta = BigDecimal.valueOf(entry.getValue().targetOpening())
                    .subtract(valveById.get(entry.getKey()).openingPercent())
                    .abs();
            if (delta.compareTo(BigDecimal.valueOf(MAX_STEP_POINTS)) > 0) {
                throw new UnprocessableException("单阀本次变化不超过 10 个百分点: valveId=" + entry.getKey()
                        + ", 变化=" + delta.stripTrailingZeros().toPlainString());
            }
        }
    }

    private void validateDailyQuota(List<AdjustmentGroupMember> members,
                                    Map<Long, Valve> valveById,
                                    Map<Long, TargetSpec> targetByValve, Instant now) {
        for (AdjustmentGroupMember member : members) {
            BigDecimal oldOpening = valveById.get(member.valveId()).openingPercent();
            BigDecimal newOpening = BigDecimal.valueOf(
                    targetByValve.get(member.valveId()).targetOpening());
            if (newOpening.compareTo(oldOpening) == 0) {
                // 不变阀不消费 24 小时次数。
                continue;
            }
            long unitId = valveById.get(member.valveId()).unitId();
            int executed = taskRepository.countExecutedSince(unitId,
                    now.minus(AdjustmentService.ADJUSTMENT_WINDOW));
            if (executed >= AdjustmentService.MAX_ADJUSTMENTS_PER_24H) {
                throw new UnprocessableException("单元 24 小时内调节次数已达上限 3 次，全组零变化: unitId="
                        + unitId);
            }
        }
    }

    private Map<Long, UnsealOrder> validateSealedValves(List<AdjustmentGroupMember> members,
                                                        Map<Long, TargetSpec> targetByValve,
                                                        Map<Long, Integer> currentVersionByValve,
                                                        Instant now) {
        Map<Long, UnsealOrder> unsealByValve = new HashMap<>();
        // 先收集全部铅封阀的解封单 ID，按 ID 升序加锁，与既有锁顺序一致。
        List<Long> unsealIds = new ArrayList<>();
        for (AdjustmentGroupMember member : members) {
            if (sealRepository.findActiveByValveId(member.valveId()).isEmpty()) {
                // 未封阀沿用原校验，无需解封单。
                continue;
            }
            TargetSpec target = targetByValve.get(member.valveId());
            if (target.unsealId() == null || target.unsealVersion() == null) {
                throw new UnprocessableException("铅封阀须逐一提供合法解封单及版本: valveId="
                        + member.valveId());
            }
            unsealIds.add(target.unsealId());
        }
        unsealIds.sort(Long::compareTo);
        Map<Long, UnsealOrder> orderById = new HashMap<>();
        for (Long unsealId : unsealIds) {
            UnsealOrder order = unsealRepository.lockById(unsealId)
                    .orElseThrow(() -> new NotFoundException("解封单不存在: unsealId=" + unsealId));
            orderById.put(unsealId, order);
        }
        for (AdjustmentGroupMember member : members) {
            TargetSpec target = targetByValve.get(member.valveId());
            if (target.unsealId() == null) {
                continue;
            }
            UnsealOrder order = orderById.get(target.unsealId());
            if (order.valveId() != member.valveId()) {
                throw new UnprocessableException("解封单不属于该阀: unsealId=" + order.id()
                        + ", valveId=" + member.valveId());
            }
            if (order.status() != UnsealOrder.UnsealStatus.ACTIVE) {
                throw new UnprocessableException("解封单已撤销或已终结，全组零变化: unsealId=" + order.id());
            }
            if (now.isBefore(order.windowStart()) || !now.isBefore(order.windowEnd())) {
                throw new UnprocessableException("解封单不在有效窗口内(UTC，半开区间): unsealId=" + order.id()
                        + ", 窗口 " + order.windowStart() + " ~ " + order.windowEnd()
                        + ", 当前 " + now);
            }
            if (order.quotaUsed() >= order.quotaTotal()) {
                throw new UnprocessableException("解封调节额度已用尽，全组零变化: unsealId=" + order.id());
            }
            if (order.version() != target.unsealVersion()) {
                throw new ConflictException("解封单版本不一致: 当前=" + order.version()
                        + ", unsealVersion=" + target.unsealVersion());
            }
            int currentVersion = currentVersionByValve.get(member.valveId());
            if (currentVersion != order.valveVersion() + order.quotaUsed()) {
                throw new UnprocessableException("阀门开度版本与解封单不一致，全组零变化: valveId="
                        + member.valveId() + ", 当前=" + currentVersion
                        + ", 解封单引用=" + order.valveVersion()
                        + ", 已消费额度=" + order.quotaUsed());
            }
            unsealByValve.put(member.valveId(), order);
        }
        return unsealByValve;
    }

    private long insertCoupledTask(long stationId, Valve valve, String requestId,
                                   BigDecimal oldOpening, BigDecimal newOpening, Instant now) {
        BigDecimal delta = newOpening.subtract(oldOpening);
        // 由变化方向推导失衡分类：开大为欠流、关小为过流，评估仍走原温差流程。
        AdjustmentTask.ImbalanceType classification = delta.signum() > 0
                ? AdjustmentTask.ImbalanceType.UNDERFLOW : AdjustmentTask.ImbalanceType.OVERFLOW;
        String taskRequestId = coupledTaskRequestId(requestId, valve.id());
        String taskFingerprint = Fingerprints.of("COUPLED-TASK", requestId,
                String.valueOf(valve.id()));
        AdjustmentTask task = new AdjustmentTask(0, stationId, valve.unitId(),
                taskRequestId, taskFingerprint, classification, delta.abs(), oldOpening, newOpening,
                AdjustmentTask.TaskStatus.EXECUTING, now, null, null, now);
        return taskRepository.insert(task);
    }

    private String coupledTaskRequestId(String requestId, long valveId) {
        return requestId + "#v" + valveId;
    }

    private GroupView replayGroupOrConflict(AdjustmentGroup existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409: requestId=" + existing.requestId());
        }
        return getGroup(existing.id());
    }

    private CoupledView replayCoupledOrConflict(CoupledAdjustment existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409: requestId=" + existing.requestId());
        }
        return getCoupled(existing.id());
    }

    private AdjustmentGroup replayDissolve(AdjustmentGroup group, String requestId,
                                           String fingerprint) {
        if (!requestId.equals(group.dissolveRequestId())) {
            throw new ConflictException("联调组已被其他请求解除: groupId=" + group.id());
        }
        if (!group.dissolveFingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409: requestId=" + requestId);
        }
        return group;
    }
}
