package com.example.starter.radio.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.domain.WithdrawalStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.WithdrawalApproval;
import com.example.starter.radio.model.WithdrawalItem;
import com.example.starter.radio.model.WithdrawalOrder;
import com.example.starter.radio.repository.ChangeOrderRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.repository.WithdrawalRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未发药整批撤回（合成数据软件验证，不用于真实给药决策）。
 * 撤回申请引用同一母液的 1~10 份不同分装、各当前有效计算版本及原因，
 * 只有“合法已计算但未发药”（CALCULATED/CHECKED）的任务可入批；
 * 未知分装、核素（母液）不一致、已拒绝、已发药或任务已属其它活动撤回单，任一项使整单拒绝，
 * 不先作废其他项；拒绝回滚不占幂等键。申请只建立 PENDING 冻结全清单，原合法发药与候选批准仍可先行。
 * 两名不同人员先后批准（每次成功版本加 1，旧 expectedVersion 409）；第二人批准在最终事务重查全清单：
 * 任一已发药或计算版本已换则全部不撤回；全合法才一起把分装转为 VOID 终态、终结其全部未生效时刻候选、
 * 使旧双人核对不再有效（签认行保留）。撤回不向母液余额加量，也不改已发药测量与对账账本。
 * 未批准可凭独立幂等键整体撤销并释放占用；已批准不能撤销。批准与发药/改版竞争只能一方先成。
 */
@Service
public class WithdrawalService {

    /** 单个撤回单允许引用的分装份数下限/上限。 */
    public static final int MIN_ITEMS = 1;
    public static final int MAX_ITEMS = 10;

    private final WithdrawalRepository withdrawals;
    private final DispensingRepository dispensings;
    private final ChangeOrderRepository changeOrders;
    private final MotherLiquorRepository motherLiquors;
    private final Clock clock;

    public WithdrawalService(WithdrawalRepository withdrawals,
                             DispensingRepository dispensings,
                             ChangeOrderRepository changeOrders,
                             MotherLiquorRepository motherLiquors,
                             Clock clock) {
        this.withdrawals = withdrawals;
        this.dispensings = dispensings;
        this.changeOrders = changeOrders;
        this.motherLiquors = motherLiquors;
        this.clock = clock;
    }

    /**
     * 创建撤回申请：仅冻结清单为 PENDING，不作废任何分装。
     * 同键同参（结构化指纹覆盖任务集合与客户端给定 UTC 精确时刻）重放首次冻结响应；同键异参 409；失败不占键。
     *
     * @param clientTimeEpoch 客户端给定的申请 UTC 精确时刻（epoch 秒），计入结构化指纹；重放须携带同一时刻
     */
    @Transactional
    public CreateResult create(String requestId, long motherLiquorId, long clientTimeEpoch,
                               String reason, List<CreateItem> items) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("整批撤回原因不能为空");
        }
        validateItems(items);
        String fingerprint = fingerprintOf(requestId, motherLiquorId, clientTimeEpoch, reason, items);

        var existing = withdrawals.findByRequestId(requestId);
        if (existing.isPresent()) {
            return new CreateResult(replayOrConflict(existing.get(), fingerprint), true);
        }

        MotherLiquor mother = motherLiquors.findById(motherLiquorId)
                .orElseThrow(() -> new NotFoundException("母液批次不存在: " + motherLiquorId));

        // 按分装主键升序加行锁，与发药/变更生效/其它撤回申请统一锁顺序，串行化竞争且避免死锁
        List<Long> dispensingIds = items.stream().map(CreateItem::dispensingId)
                .sorted().toList();
        List<Dispensing> locked = dispensingIds.stream()
                .map(id -> dispensings.findDispensingByIdForUpdate(id)
                        .orElseThrow(() -> new BusinessException(
                                "撤回清单存在未知分装: " + id + "，整单拒绝")))
                .toList();

        // 持锁后再次检查幂等键：并发同键申请在首个事务提交后于此重放首次冻结结果
        var raced = withdrawals.findByRequestId(requestId);
        if (raced.isPresent()) {
            return new CreateResult(replayOrConflict(raced.get(), fingerprint), true);
        }

        for (int i = 0; i < locked.size(); i++) {
            Dispensing d = locked.get(i);
            CreateItem item = items.stream().filter(x -> x.dispensingId() == d.id()).findFirst().orElseThrow();
            if (d.motherLiquorId() != mother.id()) {
                throw new BusinessException("分装 " + d.id() + " 不属于指定母液（核素不一致），整单拒绝");
            }
            if (d.status() == DispensingStatus.DISPENSED) {
                throw new BusinessException("分装 " + d.id() + " 已发药，整单拒绝");
            }
            if (d.status() == DispensingStatus.REJECTED) {
                throw new BusinessException("分装 " + d.id() + " 已拒绝，整单拒绝");
            }
            if (d.status() == DispensingStatus.VOID) {
                throw new BusinessException("分装 " + d.id() + " 已撤回（VOID 终态），整单拒绝");
            }
            if (item.expectedVersion() != d.versionNo()) {
                throw new ConflictException("分装 " + d.id() + " 当前有效计算版本已变化: expected="
                        + item.expectedVersion() + " actual=" + d.versionNo());
            }
            withdrawals.findActiveOrderIdByDispensing(d.id()).ifPresent(otherId -> {
                throw new BusinessException(
                        "分装 " + d.id() + " 已属于活动撤回单 " + otherId + "，任务至多属于一张活动撤回单");
            });
        }

        long now = clock.instant().getEpochSecond();
        long orderId = withdrawals.insertOrder(requestId, fingerprint, motherLiquorId,
                reason, WithdrawalStatus.PENDING, now);
        try {
            for (int i = 0; i < items.size(); i++) {
                CreateItem item = items.get(i);
                int frozenVersion = locked.stream().filter(d -> d.id() == item.dispensingId()).findFirst().orElseThrow()
                        .versionNo();
                withdrawals.insertItem(orderId, item.dispensingId(), frozenVersion, item.reason(), now);
            }
        } catch (DuplicateKeyException e) {
            // 并发占用：唯一索引兜底（dispensing_id, active_order_flag=1），整单拒绝、回滚不占键
            throw new BusinessException("清单中存在已被其它活动撤回单占用的分装，整单拒绝");
        }
        return new CreateResult(withdrawals.findOrderById(orderId).orElseThrow(), false);
    }

    /**
     * 双人批准。两名不同人员先后批准，每次成功版本加 1；第二人批准在最终事务重查全清单。
     * 最终事务内任一分装已发药/已拒绝/已撤回或冻结版本已换，则全部不撤回（撤回单停留 PENDING）。
     */
    @Transactional
    public WithdrawalOrder approve(long withdrawalOrderId, int expectedVersion, String approver) {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("批准人员不能为空");
        }
        WithdrawalOrder order = withdrawals.findOrderByIdForUpdate(withdrawalOrderId)
                .orElseThrow(() -> new NotFoundException("撤回单不存在: " + withdrawalOrderId));
        if (order.status() != WithdrawalStatus.PENDING) {
            throw new BusinessException("撤回单不处于待批准状态，禁止批准: " + order.status());
        }
        if (expectedVersion != order.versionNo()) {
            throw new ConflictException("撤回单版本已变化: expected=" + expectedVersion
                    + " actual=" + order.versionNo());
        }
        long now = clock.instant().getEpochSecond();

        if (order.firstApprover() == null) {
            int changed = withdrawals.markFirstApprover(withdrawalOrderId, approver, expectedVersion, now);
            if (changed != 1) {
                throw new ConflictException("撤回单状态已变化，第一批准未生效");
            }
            withdrawals.insertApproval(withdrawalOrderId, approver, 1, now);
            return withdrawals.findOrderById(withdrawalOrderId).orElseThrow();
        }
        if (order.firstApprover().equals(approver)) {
            throw new ConflictException("撤回批准须为两名不同人员，单人重复批准不生效: " + approver);
        }

        // 第二人：最终事务重查全清单（持撤回单行锁后，按分装主键升序再锁分装行）
        List<WithdrawalItem> frozen = withdrawals.findItems(withdrawalOrderId);
        List<Long> dispensingIds = frozen.stream().map(WithdrawalItem::dispensingId).sorted().toList();
        for (long dispensingId : dispensingIds) {
            Dispensing d = dispensings.findDispensingByIdForUpdate(dispensingId)
                    .orElseThrow(() -> new BusinessException("分装 " + dispensingId + " 已不存在，全部不撤回"));
            WithdrawalItem item = frozen.stream().filter(x -> x.dispensingId() == dispensingId).findFirst().orElseThrow();
            if (d.status() == DispensingStatus.DISPENSED) {
                throw new BusinessException("分装 " + dispensingId + " 已先行发药，全部不撤回");
            }
            if (d.status() == DispensingStatus.REJECTED || d.status() == DispensingStatus.VOID) {
                throw new BusinessException("分装 " + dispensingId + " 已处于终态 " + d.status() + "，全部不撤回");
            }
            if (d.versionNo() != item.frozenVersionNo()) {
                throw new BusinessException("分装 " + dispensingId + " 计算版本已由 " + item.frozenVersionNo()
                        + " 换为 " + d.versionNo() + "，全部不撤回");
            }
        }

        // 全合法：逐个终结未生效时刻候选（记录候选终结证据），再一起转为 VOID 终态。
        // 全程不触碰母液余额与对账台账；任一步失败整体回滚，不留部分 VOID。
        for (WithdrawalItem item : frozen) {
            List<Long> pendingCandidates = changeOrders.findPendingIdsByDispensingForUpdate(item.dispensingId());
            for (Long candidateId : pendingCandidates) {
                changeOrders.markVoidByWithdrawal(candidateId, now);
                withdrawals.markItemTerminatedCandidate(item.id(), candidateId);
            }
            int changed = dispensings.markWithdrawnVoid(item.dispensingId(), now);
            if (changed != 1) {
                throw new BusinessException("分装 " + item.dispensingId() + " 状态已变化，全部不撤回");
            }
        }
        withdrawals.insertApproval(withdrawalOrderId, approver, 2, now);
        int approved = withdrawals.markApproved(withdrawalOrderId, approver, expectedVersion, now);
        if (approved != 1) {
            // 兜底：版本/状态被并发改变，整体回滚
            throw new ConflictException("撤回单状态已变化，批准未生效");
        }
        withdrawals.releaseAllItems(withdrawalOrderId);
        return withdrawals.findOrderById(withdrawalOrderId).orElseThrow();
    }

    /**
     * 未批准整体撤销并释放全清单占用（独立幂等键）；已批准不能撤销。
     * 同键同参重放；同键引用不同撤回单 409；旧 expectedVersion 409。
     */
    @Transactional
    public WithdrawalOrder cancel(String requestId, long withdrawalOrderId, int expectedVersion) {
        var byCancelKey = withdrawals.findByCancelRequestId(requestId);
        if (byCancelKey.isPresent()) {
            WithdrawalOrder o = byCancelKey.get();
            if (o.id() != withdrawalOrderId) {
                throw new ConflictException("相同撤销 requestId 引用不同撤回单: " + requestId);
            }
            return o;
        }
        WithdrawalOrder order = withdrawals.findOrderByIdForUpdate(withdrawalOrderId)
                .orElseThrow(() -> new NotFoundException("撤回单不存在: " + withdrawalOrderId));
        if (order.status() == WithdrawalStatus.APPROVED) {
            throw new BusinessException("撤回单已批准，不能撤销");
        }
        if (order.status() != WithdrawalStatus.PENDING) {
            throw new BusinessException("撤回单不处于待批准状态，禁止撤销: " + order.status());
        }
        if (expectedVersion != order.versionNo()) {
            throw new ConflictException("撤回单版本已变化: expected=" + expectedVersion
                    + " actual=" + order.versionNo());
        }
        long now = clock.instant().getEpochSecond();
        int cancelled = withdrawals.markCancelled(withdrawalOrderId, requestId, expectedVersion, now);
        if (cancelled != 1) {
            throw new ConflictException("撤回单状态已变化，撤销未生效");
        }
        withdrawals.releaseAllItems(withdrawalOrderId);
        return withdrawals.findOrderById(withdrawalOrderId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public WithdrawalOrder getOrder(long withdrawalOrderId) {
        return withdrawals.findOrderById(withdrawalOrderId)
                .orElseThrow(() -> new NotFoundException("撤回单不存在: " + withdrawalOrderId));
    }

    /**
     * 撤回证据查询：撤回单 + 逐项冻结版本/原因/候选终结证据 + 双人批准链。
     */
    @Transactional(readOnly = true)
    public WithdrawalEvidence evidence(long withdrawalOrderId) {
        WithdrawalOrder order = getOrder(withdrawalOrderId);
        List<WithdrawalItem> items = withdrawals.findItems(withdrawalOrderId);
        List<WithdrawalApproval> approvals = withdrawals.findApprovals(withdrawalOrderId);
        return new WithdrawalEvidence(order, items, approvals);
    }

    private void validateItems(List<CreateItem> items) {
        if (items == null || items.size() < MIN_ITEMS || items.size() > MAX_ITEMS) {
            throw new BusinessException("撤回申请须引用 " + MIN_ITEMS + "~" + MAX_ITEMS + " 份分装");
        }
        Set<Long> distinct = new HashSet<>();
        for (CreateItem item : items) {
            if (item.reason() == null || item.reason().isBlank()) {
                throw new IllegalArgumentException("每份分装须给出撤回原因");
            }
            if (item.expectedVersion() < 1) {
                throw new IllegalArgumentException("分装计算版本号须从 1 开始: dispensingId=" + item.dispensingId());
            }
            if (!distinct.add(item.dispensingId())) {
                throw new BusinessException("撤回清单存在重复分装: " + item.dispensingId());
            }
        }
    }

    /**
     * 结构化指纹：长度前缀无歧义编码，覆盖 requestId、母液、UTC 精确时刻
     * 与按分装主键排序的任务集合（每份含冻结期望版本与原因）。
     */
    private String fingerprintOf(String requestId, long motherLiquorId, long clientTimeEpoch,
                                 String reason, List<CreateItem> items) {
        List<CreateItem> sorted = items.stream().sorted(java.util.Comparator.comparingLong(CreateItem::dispensingId))
                .toList();
        Object[] fields = new Object[5 + sorted.size() * 3];
        fields[0] = "WITHDRAWAL-CREATE";
        fields[1] = requestId;
        fields[2] = motherLiquorId;
        fields[3] = clientTimeEpoch;
        fields[4] = reason;
        int idx = 5;
        for (CreateItem item : sorted) {
            fields[idx++] = item.dispensingId();
            fields[idx++] = item.expectedVersion();
            fields[idx++] = item.reason();
        }
        return Fingerprints.structured(fields);
    }

    private WithdrawalOrder replayOrConflict(WithdrawalOrder existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 但业务字段不一致，拒绝重复受理: " + existing.requestId());
        }
        return existing;
    }

    /** 申请清单项：引用分装、客户端持有的当前有效计算版本（从 1 开始）与撤回原因。 */
    public record CreateItem(long dispensingId, int expectedVersion, String reason) {
    }

    /** 创建结果。replayed 为 true 表示命中幂等重放，未产生新撤回单与冻结行。 */
    public record CreateResult(WithdrawalOrder order, boolean replayed) {
    }

    /** 撤回证据：撤回单、逐项冻结清单与双人批准链。 */
    public record WithdrawalEvidence(WithdrawalOrder order, List<WithdrawalItem> items,
                                     List<WithdrawalApproval> approvals) {
    }
}
