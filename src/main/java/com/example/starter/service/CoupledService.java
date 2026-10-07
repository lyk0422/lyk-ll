package com.example.starter.service;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.CoupledAdjustment;
import com.example.starter.domain.CoupledAdjustmentItem;
import com.example.starter.domain.CoupledGroup;
import com.example.starter.domain.CoupledGroupMember;
import com.example.starter.domain.HeatingUnit;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.Valve;
import com.example.starter.domain.ValveSeal;
import com.example.starter.repository.AdjustmentTaskRepository;
import com.example.starter.repository.CoupledAdjustmentRepository;
import com.example.starter.repository.CoupledGroupRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 耦合联调服务：联调组建/改/解除与全组目标开度的原子提交。
 * 锁顺序约定：station → valve（按阀门 ID 升序）→ unseal（按解封单 ID 升序），
 * 与单阀调节、铅封登记/复封一致，避免死锁。
 * 联调与单阀调节或复封竞争按共享阀版本提交：任一阀校验失败全组零变化。
 */
@Service
public class CoupledService {

    /** 联调组最少成员阀数。 */
    public static final int MIN_MEMBERS = 2;
    /** 联调组最多成员阀数。 */
    public static final int MAX_MEMBERS = 5;
    /** 联调权重下限（正整数）。 */
    public static final int MIN_WEIGHT = 1;
    /** 联调权重上限。 */
    public static final int MAX_WEIGHT = 10;

    private final StationRepository stationRepository;
    private final UnitRepository unitRepository;
    private final ValveRepository valveRepository;
    private final ValveVersionRepository versionRepository;
    private final AdjustmentTaskRepository taskRepository;
    private final ValveSealRepository sealRepository;
    private final UnsealOrderRepository unsealRepository;
    private final UnsealQuotaUsageRepository usageRepository;
    private final CoupledGroupRepository groupRepository;
    private final CoupledAdjustmentRepository adjustmentRepository;
    private final Clock clock;

    public CoupledService(StationRepository stationRepository,
                          UnitRepository unitRepository,
                          ValveRepository valveRepository,
                          ValveVersionRepository versionRepository,
                          AdjustmentTaskRepository taskRepository,
                          ValveSealRepository sealRepository,
                          UnsealOrderRepository unsealRepository,
                          UnsealQuotaUsageRepository usageRepository,
                          CoupledGroupRepository groupRepository,
                          CoupledAdjustmentRepository adjustmentRepository,
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
        this.adjustmentRepository = adjustmentRepository;
        this.clock = clock;
    }

    /**
     * 建组成员指令。
     *
     * @param valveId 成员阀门 ID
     * @param weight  联调权重，正整数 1~10，建组时冻结
     */
    public record MemberCommand(long valveId, int weight) {
    }

    /**
     * 联调逐阀目标指令。
     *
     * @param valveId        成员阀门 ID
     * @param targetOpening  目标开度，0~100 整数百分点
     * @param currentVersion 客户端持有的阀门当前开度版本（无版本为 0），旧版 409
     * @param unsealId       铅封阀提供的解封单 ID；未封阀为 null
     */
    public record TargetCommand(long valveId, int targetOpening, int currentVersion, Long unsealId) {
    }

    /**
     * 联调组视图：组单与冻结成员。
     */
    public record GroupView(CoupledGroup group, List<CoupledGroupMember> members) {
    }

    /**
     * 联调单视图：联调单、逐阀明细与净加权开度（可由明细复算）。
     *
     * @param weightedBefore 加权调前开度总和 Σ(weight × oldOpening)
     * @param weightedAfter  加权目标开度总和 Σ(weight × newOpening)，守恒校验要求与调前精确相等
     */
    public record AdjustmentView(CoupledAdjustment adjustment, List<CoupledAdjustmentItem> items,
                                 BigDecimal weightedBefore, BigDecimal weightedAfter) {
    }

    /**
     * 建立联调组：同站 2~5 只不同阀，冻结各阀权重 1~10 及当前开度版本，
     * 阀只能属于一个活动组；requestId 幂等，同参重放、异参 409、失败不占键。
     */
    @Transactional
    public GroupView createGroup(long stationId, String requestId, List<MemberCommand> members) {
        List<MemberCommand> safeMembers = members == null ? List.of() : members;
        String fingerprint = groupFingerprint(requestId, stationId, safeMembers);
        var existing = groupRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayGroupOrConflict(existing.get(), fingerprint);
        }
        validateMembers(safeMembers);
        // 行级锁串行化站内建组/改组/解除与联调提交，保证活动组唯一性检查可靠。
        stationRepository.lockById(stationId)
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + stationId));
        var existingAfterLock = groupRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayGroupOrConflict(existingAfterLock.get(), fingerprint);
        }
        for (MemberCommand member : safeMembers) {
            requireStationValve(stationId, member.valveId());
            if (groupRepository.findActiveGroupByValveId(member.valveId()).isPresent()) {
                throw new UnprocessableException(
                        "阀门已属于其他活动联调组，阀只能属于一个活动组: valveId=" + member.valveId());
            }
        }
        CoupledGroup group = new CoupledGroup(0, stationId, CoupledGroup.GroupStatus.ACTIVE, 1,
                requestId, fingerprint, clock.instant(), null);
        long groupId;
        try {
            groupId = groupRepository.insert(group);
        } catch (DuplicateKeyException e) {
            // 并发下同键插入：唯一约束保证只建组一次，重放首个结果。
            return groupRepository.findByRequestId(requestId)
                    .map(g -> replayGroupOrConflict(g, fingerprint))
                    .orElseThrow(() -> e);
        }
        for (MemberCommand member : safeMembers) {
            groupRepository.insertMember(groupId, member.valveId(), member.weight(),
                    versionRepository.maxVersionNo(member.valveId()));
        }
        return getGroup(groupId);
    }

    /**
     * 修改联调组：整体替换成员并重新冻结开度版本；
     * expectedVersion 乐观锁，成功加 1，旧版 409。
     */
    @Transactional
    public GroupView modifyGroup(long groupId, int expectedVersion, List<MemberCommand> members) {
        List<MemberCommand> safeMembers = members == null ? List.of() : members;
        CoupledGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        validateMembers(safeMembers);
        long stationId = group.stationId();
        stationRepository.lockById(stationId)
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + stationId));
        group = groupRepository.findById(groupId).orElseThrow();
        if (group.status() != CoupledGroup.GroupStatus.ACTIVE) {
            throw new UnprocessableException("联调组已解除，禁止修改: groupId=" + groupId);
        }
        if (group.version() != expectedVersion) {
            throw new ConflictException("联调单版本不一致: 当前=" + group.version()
                    + ", expectedVersion=" + expectedVersion);
        }
        for (MemberCommand member : safeMembers) {
            requireStationValve(group.stationId(), member.valveId());
            var activeGroup = groupRepository.findActiveGroupByValveId(member.valveId());
            if (activeGroup.isPresent() && activeGroup.get().id() != groupId) {
                throw new UnprocessableException(
                        "阀门已属于其他活动联调组，阀只能属于一个活动组: valveId=" + member.valveId());
            }
        }
        groupRepository.deleteMembers(groupId);
        for (MemberCommand member : safeMembers) {
            groupRepository.insertMember(groupId, member.valveId(), member.weight(),
                    versionRepository.maxVersionNo(member.valveId()));
        }
        int bumped = groupRepository.bumpVersion(groupId, expectedVersion);
        if (bumped != 1) {
            throw new ConflictException("联调组并发变更，修改失败: groupId=" + groupId);
        }
        return getGroup(groupId);
    }

    /**
     * 解除联调组：只释放组归属，不回拨开度或返还已消费额度；
     * expectedVersion 乐观锁，成功加 1，旧版 409。
     */
    @Transactional
    public GroupView dissolveGroup(long groupId, int expectedVersion) {
        CoupledGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        long stationId = group.stationId();
        stationRepository.lockById(stationId)
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + stationId));
        group = groupRepository.findById(groupId).orElseThrow();
        if (group.status() != CoupledGroup.GroupStatus.ACTIVE) {
            throw new UnprocessableException("联调组已解除: groupId=" + groupId);
        }
        if (group.version() != expectedVersion) {
            throw new ConflictException("联调单版本不一致: 当前=" + group.version()
                    + ", expectedVersion=" + expectedVersion);
        }
        int updated = groupRepository.dissolve(groupId, expectedVersion, clock.instant());
        if (updated != 1) {
            throw new ConflictException("联调组并发变更，解除失败: groupId=" + groupId);
        }
        return getGroup(groupId);
    }

    /**
     * 联调提交：全组目标开度与逐阀当前版本原子生效。
     * 加权目标总和须精确等于加权调前开度总和（不舍入）；每阀变化不超过 10 个百分点
     * （恰等允许）且仍守 24 小时最多 3 次；铅封阀逐一提供合法解封单（半开有效窗口及
     * 剩余额度），未封阀沿用原校验。缺阀、旧版本、失效解封或任一超限全组零变化；
     * 合法才原子生成联调记录、更新变化阀开度版本并对每个实际变化阀消费一次额度和
     * 次数，不变阀不消费。requestId 幂等：同参重放、异参 409、失败不占键。
     */
    @Transactional
    public AdjustmentView submitAdjustment(long groupId, String requestId, Instant adjustAt,
                                           List<TargetCommand> targets) {
        List<TargetCommand> safeTargets = targets == null ? List.of() : targets;
        CoupledGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        var existing = adjustmentRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayAdjustmentOrConflict(existing.get(), requestId, groupId, adjustAt, safeTargets);
        }
        if (adjustAt == null) {
            throw new UnprocessableException("联调时刻 adjustAt 不能为空(UTC)");
        }
        if (safeTargets.isEmpty()) {
            throw new UnprocessableException("联调目标不能为空");
        }
        // 行级锁串行化站内联调提交、单阀调节与建组/改组/解除。
        long stationId = group.stationId();
        stationRepository.lockById(stationId)
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + stationId));
        // 持锁后重查幂等键：并发下同键请求在前序事务提交后按重放处理。
        var existingAfterLock = adjustmentRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayAdjustmentOrConflict(existingAfterLock.get(), requestId, groupId,
                    adjustAt, safeTargets);
        }
        group = groupRepository.findById(groupId).orElseThrow();
        if (group.status() != CoupledGroup.GroupStatus.ACTIVE) {
            throw new UnprocessableException("联调组已解除，禁止联调: groupId=" + groupId);
        }
        List<CoupledGroupMember> members = groupRepository.findMembers(groupId);
        String fingerprint = adjustmentFingerprint(requestId, groupId, adjustAt, safeTargets,
                weightsOf(members));
        // 目标集合须恰好覆盖全组成员阀：缺阀、多阀或重复阀全组零变化。
        Map<Long, TargetCommand> targetMap = new TreeMap<>();
        for (TargetCommand target : safeTargets) {
            if (target.targetOpening() < 0 || target.targetOpening() > 100) {
                throw new UnprocessableException("目标开度须为 0~100 整数百分点: "
                        + target.targetOpening());
            }
            if (targetMap.put(target.valveId(), target) != null) {
                throw new UnprocessableException("联调目标存在重复阀门: valveId=" + target.valveId());
            }
        }
        Map<Long, CoupledGroupMember> memberMap = new TreeMap<>();
        for (CoupledGroupMember member : members) {
            memberMap.put(member.valveId(), member);
        }
        if (!targetMap.keySet().equals(memberMap.keySet())) {
            throw new UnprocessableException("联调目标须恰好覆盖全组成员阀（缺阀或多阀全组零变化）: 成员="
                    + memberMap.keySet() + ", 提交=" + targetMap.keySet());
        }
        Instant now = clock.instant();
        // 按阀门 ID 升序加行锁，与单阀调节/复封共享阀版本竞争。
        Map<Long, Valve> valves = new TreeMap<>();
        for (CoupledGroupMember member : members) {
            Valve valve = valveRepository.lockById(member.valveId())
                    .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + member.valveId()));
            valves.put(valve.id(), valve);
        }
        // 铅封阀逐一要求解封单；按解封单 ID 升序加锁。
        Map<Long, ValveSeal> activeSeals = new TreeMap<>();
        TreeSet<Long> unsealIds = new TreeSet<>();
        for (CoupledGroupMember member : members) {
            var seal = sealRepository.findActiveByValveId(member.valveId());
            if (seal.isPresent()) {
                activeSeals.put(member.valveId(), seal.get());
                Long unsealId = targetMap.get(member.valveId()).unsealId();
                if (unsealId == null) {
                    throw new UnprocessableException(
                            "铅封阀须逐一提供合法解封单: valveId=" + member.valveId());
                }
                unsealIds.add(unsealId);
            }
        }
        Map<Long, UnsealOrder> unsealOrders = new HashMap<>();
        for (Long unsealId : unsealIds) {
            UnsealOrder order = unsealRepository.lockById(unsealId)
                    .orElseThrow(() -> new NotFoundException("解封单不存在: unsealId=" + unsealId));
            unsealOrders.put(unsealId, order);
        }
        // 最终事务检查全部阀及解封状态，任一失败全组零变化。
        List<PlanItem> plan = new ArrayList<>();
        BigDecimal weightedBefore = BigDecimal.ZERO;
        BigDecimal weightedAfter = BigDecimal.ZERO;
        for (CoupledGroupMember member : members) {
            long valveId = member.valveId();
            Valve valve = valves.get(valveId);
            TargetCommand target = targetMap.get(valveId);
            BigDecimal current = valve.openingPercent();
            BigDecimal targetOpening = BigDecimal.valueOf(target.targetOpening());
            BigDecimal weight = BigDecimal.valueOf(member.weight());
            weightedBefore = weightedBefore.add(weight.multiply(current));
            weightedAfter = weightedAfter.add(weight.multiply(targetOpening));
            BigDecimal delta = targetOpening.subtract(current).abs();
            if (delta.compareTo(AdjustmentService.MAX_STEP_PERCENT) > 0) {
                throw new UnprocessableException("单阀变化超过 10 个百分点: valveId=" + valveId
                        + ", 变化=" + delta.stripTrailingZeros().toPlainString());
            }
            int currentVersion = versionRepository.maxVersionNo(valveId);
            if (currentVersion != target.currentVersion()) {
                throw new ConflictException("阀门开度版本不一致（旧版本），全组零变化: valveId=" + valveId
                        + ", 当前=" + currentVersion + ", 提交=" + target.currentVersion());
            }
            boolean changed = delta.compareTo(BigDecimal.ZERO) > 0;
            if (changed) {
                int executed = taskRepository.countExecutedSince(valve.unitId(),
                        now.minus(AdjustmentService.ADJUSTMENT_WINDOW));
                if (executed >= AdjustmentService.MAX_ADJUSTMENTS_PER_24H) {
                    throw new UnprocessableException("阀门 24 小时内调节已达 3 次上限，全组零变化: valveId="
                            + valveId);
                }
            }
            UnsealOrder order = null;
            if (activeSeals.containsKey(valveId)) {
                order = unsealOrders.get(target.unsealId());
                if (order.valveId() != valveId) {
                    throw new UnprocessableException("解封单不属于该阀: unsealId=" + order.id()
                            + ", valveId=" + valveId);
                }
                if (order.status() != UnsealOrder.UnsealStatus.ACTIVE) {
                    throw new UnprocessableException("解封单已撤销或已终结，全组零变化: unsealId=" + order.id());
                }
                if (now.isBefore(order.windowStart()) || !now.isBefore(order.windowEnd())) {
                    throw new UnprocessableException("不在解封有效窗口内(UTC，半开区间)，全组零变化: 窗口 "
                            + order.windowStart() + " ~ " + order.windowEnd() + ", 当前 " + now);
                }
                if (order.quotaUsed() >= order.quotaTotal()) {
                    throw new UnprocessableException("解封调节额度已用尽，全组零变化: unsealId=" + order.id());
                }
                if (currentVersion != order.valveVersion() + order.quotaUsed()) {
                    throw new UnprocessableException("阀门开度版本与解封单不一致，全组零变化: valveId=" + valveId
                            + ", 当前=" + currentVersion + ", 解封单引用=" + order.valveVersion()
                            + ", 已消费额度=" + order.quotaUsed());
                }
            }
            plan.add(new PlanItem(member, valve, current, targetOpening, delta, changed,
                    currentVersion, order));
        }
        // 加权目标总和须精确等于加权调前开度总和，不舍入。
        if (weightedBefore.compareTo(weightedAfter) != 0) {
            throw new UnprocessableException("加权目标总和须等于加权调前开度总和（精确比较不舍入）: 调前="
                    + weightedBefore.stripTrailingZeros().toPlainString() + ", 目标="
                    + weightedAfter.stripTrailingZeros().toPlainString());
        }
        // 全部合法：原子生成联调记录、更新变化阀开度版本并消费额度和次数。
        CoupledAdjustment adjustment = new CoupledAdjustment(0, groupId, group.stationId(),
                requestId, fingerprint, adjustAt, now, 1, now);
        long coupledId;
        try {
            coupledId = adjustmentRepository.insert(adjustment);
        } catch (DuplicateKeyException e) {
            // 并发下同键插入：唯一约束保证只受理一次，重放首个结果。
            return adjustmentRepository.findByRequestId(requestId)
                    .map(a -> replayAdjustmentOrConflict(a, requestId, groupId, adjustAt, safeTargets))
                    .orElseThrow(() -> e);
        }
        List<CoupledAdjustmentItem> items = new ArrayList<>();
        for (PlanItem item : plan) {
            Long taskId = null;
            if (item.changed()) {
                // 不变阀不消费：仅实际变化阀生成任务、推进版本并消费额度和次数。
                String itemRequestId = requestId + "#v" + item.valve().id();
                AdjustmentTask task = new AdjustmentTask(0, group.stationId(), item.valve().unitId(),
                        itemRequestId,
                        Fingerprints.of("CITEM", requestId, String.valueOf(item.valve().id())),
                        null, item.delta(), item.current(), item.targetOpening(),
                        AdjustmentTask.TaskStatus.EXECUTING, now, null, null, now);
                long newTaskId = taskRepository.insert(task);
                taskId = newTaskId;
                valveRepository.updateOpening(item.valve().id(), item.targetOpening(), now);
                versionRepository.insert(item.valve().id(), newTaskId, item.currentVersion() + 1,
                        item.current(), item.targetOpening(), now);
                if (item.order() != null) {
                    int consumed = unsealRepository.consumeQuota(item.order().id(),
                            item.order().version());
                    if (consumed != 1) {
                        throw new ConflictException("解封单并发变更，额度消费失败: unsealId="
                                + item.order().id());
                    }
                    usageRepository.insert(item.order().id(), newTaskId, itemRequestId, now);
                }
            }
            adjustmentRepository.insertItem(coupledId, item.member().valveId(), item.member().weight(),
                    item.current(), item.targetOpening(),
                    item.order() == null ? null : item.order().id(), taskId);
        }
        CoupledAdjustment saved = adjustmentRepository.findById(coupledId).orElseThrow();
        return new AdjustmentView(saved, adjustmentRepository.findItems(coupledId),
                weightedBefore, weightedAfter);
    }

    /**
     * 查询联调组及冻结成员。
     */
    @Transactional(readOnly = true)
    public GroupView getGroup(long groupId) {
        CoupledGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        return new GroupView(group, groupRepository.findMembers(groupId));
    }

    /**
     * 查询联调单及逐阀明细，净加权开度可由明细复算。
     */
    @Transactional(readOnly = true)
    public AdjustmentView getAdjustment(long coupledId) {
        CoupledAdjustment adjustment = adjustmentRepository.findById(coupledId)
                .orElseThrow(() -> new NotFoundException("联调单不存在: coupledId=" + coupledId));
        return toView(adjustment, adjustmentRepository.findItems(coupledId));
    }

    /**
     * 查询联调组全部联调单，按主键升序。
     */
    @Transactional(readOnly = true)
    public List<AdjustmentView> listGroupAdjustments(long groupId) {
        groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("联调组不存在: groupId=" + groupId));
        List<AdjustmentView> views = new ArrayList<>();
        for (CoupledAdjustment adjustment : adjustmentRepository.findByGroupId(groupId)) {
            views.add(toView(adjustment, adjustmentRepository.findItems(adjustment.id())));
        }
        return views;
    }

    /**
     * 联调执行计划项：单阀校验通过后待原子生效的变更。
     */
    private record PlanItem(CoupledGroupMember member, Valve valve,
                            BigDecimal current, BigDecimal targetOpening, BigDecimal delta,
                            boolean changed, int currentVersion, UnsealOrder order) {
    }

    private void validateMembers(List<MemberCommand> members) {
        if (members.size() < MIN_MEMBERS || members.size() > MAX_MEMBERS) {
            throw new UnprocessableException("联调组须含 2~5 只不同阀: 当前=" + members.size());
        }
        TreeSet<Long> valveIds = new TreeSet<>();
        for (MemberCommand member : members) {
            if (member.weight() < MIN_WEIGHT || member.weight() > MAX_WEIGHT) {
                throw new UnprocessableException("联调权重须为 1~10 正整数: valveId=" + member.valveId()
                        + ", weight=" + member.weight());
            }
            if (!valveIds.add(member.valveId())) {
                throw new UnprocessableException("联调组成员阀重复: valveId=" + member.valveId());
            }
        }
    }

    private void requireStationValve(long stationId, long valveId) {
        Valve valve = valveRepository.findById(valveId)
                .orElseThrow(() -> new NotFoundException("阀门不存在: valveId=" + valveId));
        HeatingUnit unit = unitRepository.findById(valve.unitId())
                .orElseThrow(() -> new NotFoundException("单元不存在: unitId=" + valve.unitId()));
        if (unit.stationId() != stationId) {
            throw new UnprocessableException("阀门不属于该换热站: valveId=" + valveId
                    + ", stationId=" + stationId);
        }
    }

    private GroupView replayGroupOrConflict(CoupledGroup existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409: requestId=" + existing.requestId());
        }
        return new GroupView(existing, groupRepository.findMembers(existing.id()));
    }

    private AdjustmentView replayAdjustmentOrConflict(CoupledAdjustment existing, String requestId,
                                                      long groupId, Instant adjustAt,
                                                      List<TargetCommand> targets) {
        List<CoupledAdjustmentItem> items = adjustmentRepository.findItems(existing.id());
        // 重放指纹使用首次受理时冻结的权重，改组不影响已受理联调单的幂等重放。
        Map<Long, Integer> storedWeights = new TreeMap<>();
        for (CoupledAdjustmentItem item : items) {
            storedWeights.put(item.valveId(), item.weight());
        }
        String expected = adjustmentFingerprint(requestId, groupId, adjustAt, targets, storedWeights);
        if (!existing.fingerprint().equals(expected)) {
            throw new ConflictException("同键异参，返回 409 且不改变阀门开度: requestId="
                    + existing.requestId());
        }
        return toView(existing, items);
    }

    private AdjustmentView toView(CoupledAdjustment adjustment, List<CoupledAdjustmentItem> items) {
        BigDecimal weightedBefore = BigDecimal.ZERO;
        BigDecimal weightedAfter = BigDecimal.ZERO;
        for (CoupledAdjustmentItem item : items) {
            BigDecimal weight = BigDecimal.valueOf(item.weight());
            weightedBefore = weightedBefore.add(weight.multiply(item.oldOpening()));
            weightedAfter = weightedAfter.add(weight.multiply(item.newOpening()));
        }
        return new AdjustmentView(adjustment, items, weightedBefore, weightedAfter);
    }

    private static Map<Long, Integer> weightsOf(List<CoupledGroupMember> members) {
        Map<Long, Integer> weights = new TreeMap<>();
        for (CoupledGroupMember member : members) {
            weights.put(member.valveId(), member.weight());
        }
        return weights;
    }

    /**
     * 建组幂等指纹：requestId、换热站与全阀集合及权重。
     */
    private static String groupFingerprint(String requestId, long stationId,
                                           List<MemberCommand> members) {
        List<MemberCommand> sorted = new ArrayList<>(members);
        sorted.sort((a, b) -> Long.compare(a.valveId(), b.valveId()));
        StringBuilder memberPart = new StringBuilder();
        for (MemberCommand member : sorted) {
            memberPart.append(member.valveId()).append(':').append(member.weight()).append(',');
        }
        return Fingerprints.of("CGROUP", requestId, String.valueOf(stationId),
                memberPart.toString());
    }

    /**
     * 联调幂等指纹：requestId、联调组、全阀集合与权重、逐阀目标/版本/解封单及 UTC 时刻。
     */
    private static String adjustmentFingerprint(String requestId, long groupId, Instant adjustAt,
                                                List<TargetCommand> targets,
                                                Map<Long, Integer> weights) {
        StringBuilder weightPart = new StringBuilder();
        for (Map.Entry<Long, Integer> entry : weights.entrySet()) {
            weightPart.append(entry.getKey()).append(':').append(entry.getValue()).append(',');
        }
        List<TargetCommand> sorted = new ArrayList<>(targets);
        sorted.sort((a, b) -> Long.compare(a.valveId(), b.valveId()));
        StringBuilder targetPart = new StringBuilder();
        for (TargetCommand target : sorted) {
            targetPart.append(target.valveId()).append(':').append(target.targetOpening())
                    .append(':').append(target.currentVersion()).append(':')
                    .append(target.unsealId() == null ? "-" : target.unsealId()).append(',');
        }
        return Fingerprints.of("CADJUST", requestId, String.valueOf(groupId),
                adjustAt == null ? null : adjustAt.toString(),
                weightPart.toString(), targetPart.toString());
    }
}
