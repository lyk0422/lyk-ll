package com.example.starter.radio.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.domain.WithdrawalStatus;
import com.example.starter.radio.model.ChangeOrder;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.WithdrawalApproval;
import com.example.starter.radio.model.WithdrawalItem;
import com.example.starter.radio.model.WithdrawalOrder;
import com.example.starter.radio.repository.ChangeOrderRepository;
import com.example.starter.radio.repository.DispensingRepository;
import com.example.starter.radio.repository.WithdrawalRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 未发药整批撤回（合成数据软件验证，不用于真实给药决策）。
 *
 * <p>申请引用同一母液的 1 至 10 份不同分装，每项携带当前有效计算版本与逐项原因；
 * 只有“合法已计算但未发药”（CALCULATED/CHECKED）的任务可入批。未知分装、核素（母液）不一致、
 * 已拒绝或已发药任一命中即整单拒绝，不先作废其它任何项。申请只创建 PENDING 并冻结全清单，
 * 任务至多属于一张活动撤回单（{@code active_withdrawal_id} 软占用）；占用不阻止原合法发药与候选批准。
 *
 * <p>两名不同人员批准：第二人批准在最终事务内重查全清单，任一已发药或计算版本已换则全部不撤回（整体回滚，
 * 不留部分 VOID）；全合法才同事务把分装一起转为 VOID 终态、结束其全部未生效（PENDING）时刻候选、
 * 使双人核对不再有效。原计算版本与签认不删除；VOID 不得重新激活。
 * 基线计算不扣母液库存，故撤回不向母液余额加量，也不改已发药测量与对账账本。
 *
 * <p>PENDING 可整体撤销并释放占用，已批准不能撤销。撤回单版本从 1 起：批准/撤销携带 expectedVersion，
 * 成功加 1，旧版 409。提交幂等：完整结构化指纹含任务集合（分装+版本+原因）与创建 UTC 精确时刻；
 * 同 requestId 同参重放首次响应，异参 409，失败不占键。
 *
 * <p>锁顺序：先撤回单行（FOR UPDATE），再按分装主键升序锁分装行，最后候选行；与发药/候选生效路径
 * （先分装行后候选行）构成无环等待图，批准与发药/改版竞争仅一方先成。
 */
@Service
public class WithdrawalService {

    /** 单张撤回单允许的分装项数量范围：1 至 10 份。 */
    public static final int MIN_ITEMS = 1;
    public static final int MAX_ITEMS = 10;

    private final WithdrawalRepository withdrawals;
    private final DispensingRepository dispensings;
    private final ChangeOrderRepository changeOrders;
    private final Clock clock;

    public WithdrawalService(WithdrawalRepository withdrawals,
                             DispensingRepository dispensings,
                             ChangeOrderRepository changeOrders,
                             Clock clock) {
        this.withdrawals = withdrawals;
        this.dispensings = dispensings;
        this.changeOrders = changeOrders;
        this.clock = clock;
    }

    /** 撤回申请清单项（客户端引用）。 */
    public record RequestItem(long dispensingId, int expectedVersion, String reason) {
    }

    /** 提交结果。replayed 为 true 表示命中同键同参幂等重放，未产生新撤回单与占用。 */
    public record SubmitResult(WithdrawalOrder order, List<WithdrawalItem> items, boolean replayed) {
    }

    /** 撤回单证据：冻结清单（逐项版本+原因）、双人批准链与被终结候选。 */
    public record WithdrawalEvidence(WithdrawalOrder order,
                                     List<WithdrawalItem> items,
                                     List<WithdrawalApproval> approvals,
                                     List<ChangeOrder> terminatedCandidates) {
    }

    /**
     * 提交整批撤回申请：冻结全清单但不立即作废。
     */
    @Transactional
    public SubmitResult submit(String requestId, String reason, List<RequestItem> requestItems) {
        List<RequestItem> items = normalize(requestId, requestItems);
        String contentFingerprint = contentFingerprint(requestId, reason, items);

        var existing = withdrawals.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), contentFingerprint);
        }

        List<Long> ids = items.stream().map(RequestItem::dispensingId).sorted().distinct().toList();
        // 按主键升序锁全部分装：统一锁顺序，串行化重叠清单的并发申请与发药/改版
        List<Dispensing> locked = dispensings.findByIdsForUpdateOrdered(ids);
        // 持锁后再查幂等键：并发同键申请在首个事务提交后于此重放首次冻结结果
        var raced = withdrawals.findByRequestId(requestId);
        if (raced.isPresent()) {
            return replayOrConflict(raced.get(), contentFingerprint);
        }
        if (locked.size() != ids.size()) {
            Set<Long> found = locked.stream().map(Dispensing::id).collect(java.util.stream.Collectors.toSet());
            long missing = ids.stream().filter(id -> !found.contains(id)).findFirst().orElse(0L);
            throw new NotFoundException("分装批次不存在: " + missing);
        }

        long motherLiquorId = locked.get(0).motherLiquorId();
        for (Dispensing d : locked) {
            // 同一母液即同一核素；跨母液/核素不一致整单拒绝
            if (d.motherLiquorId() != motherLiquorId) {
                throw new BusinessException("撤回申请必须引用同一母液且核素一致，跨母液整单拒绝: dispensingId="
                        + d.id());
            }
            if (d.status() == DispensingStatus.DISPENSED) {
                throw new BusinessException("分装已发药，整单拒绝且不先作废其它项: dispensingId=" + d.id());
            }
            if (d.status() == DispensingStatus.REJECTED) {
                throw new BusinessException("分装已拒绝，整单拒绝: dispensingId=" + d.id());
            }
            if (d.status() == DispensingStatus.VOID) {
                throw new BusinessException("分装已撤回作废（VOID 终态），整单拒绝: dispensingId=" + d.id());
            }
            if (d.activeWithdrawalId() != null) {
                throw new BusinessException("分装已属于一张活动撤回单，任务至多属于一张活动撤回单: dispensingId="
                        + d.id() + ", withdrawalId=" + d.activeWithdrawalId());
            }
        }
        // 版本一致性：每项携带的当前有效计算版本必须与库内一致，否则 409（在任何作废前整体拒绝）
        for (RequestItem item : items) {
            Dispensing d = byId(locked, item.dispensingId());
            if (item.expectedVersion() != d.versionNo()) {
                throw new ConflictException("分装计算版本已变化: dispensingId=" + item.dispensingId()
                        + " expected=" + item.expectedVersion() + " actual=" + d.versionNo());
            }
        }

        long now = clock.instant().getEpochSecond();
        String fingerprint = fullFingerprint(contentFingerprint, now);
        try {
            long orderId = withdrawals.insertOrder(requestId, fingerprint, contentFingerprint,
                    motherLiquorId, reason, now);
            List<WithdrawalItem> frozen = new ArrayList<>();
            for (RequestItem item : items) {
                withdrawals.insertItem(orderId, item.dispensingId(), item.expectedVersion(), item.reason(), now);
                frozen.add(new WithdrawalItem(0L, orderId, item.dispensingId(), item.expectedVersion(),
                        item.reason(), now));
            }
            // 同事务冻结占用：任一分装已被他人抢先占用（唯一软引用）则整体回滚
            int marked = dispensings.markActiveWithdrawal(ids, orderId, now);
            if (marked != ids.size()) {
                throw new BusinessException("分装撤回占用登记数量不一致，整单拒绝");
            }
            WithdrawalOrder saved = withdrawals.findById(orderId).orElseThrow();
            return new SubmitResult(saved, withdrawals.findItems(orderId), false);
        } catch (DuplicateKeyException e) {
            return withdrawals.findByRequestId(requestId)
                    .map(o -> replayOrConflict(o, contentFingerprint))
                    .orElseThrow(() -> new IllegalStateException("撤回单写入冲突后读取失败", e));
        }
    }

    /**
     * 双人批准。两名不同人员先后批准；单人重复批准 409。仅 PENDING 可批准。
     * 第二人批准触发最终事务重查：全合法才一起 VOID；任一不合法整体回滚（全部不撤回）。
     *
     * @param expectedVersion 客户端持有的撤回单当前版本号；成功加 1，旧版 409
     */
    @Transactional
    public WithdrawalOrder approve(long withdrawalId, String approver, int expectedVersion) {
        WithdrawalOrder order = withdrawals.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new NotFoundException("撤回单不存在: " + withdrawalId));
        if (order.status() != WithdrawalStatus.PENDING) {
            throw new BusinessException("撤回单不处于待批准状态，禁止批准: " + order.status());
        }
        if (expectedVersion != order.versionNo()) {
            throw new ConflictException("撤回单版本已变化: expected=" + expectedVersion
                    + " actual=" + order.versionNo());
        }
        long now = clock.instant().getEpochSecond();

        if (order.firstApprover() == null) {
            int rows = withdrawals.markFirstApprover(withdrawalId, approver, expectedVersion, now);
            if (rows != 1) {
                throw new ConflictException("撤回单首个批准写入失败（版本或状态已变化）: " + withdrawalId);
            }
            withdrawals.insertApproval(withdrawalId, approver, 1, now);
            return withdrawals.findById(withdrawalId).orElseThrow();
        }
        if (order.firstApprover().equals(approver)) {
            throw new ConflictException("整批撤回须两名不同人员批准，单人重复批准不生效: " + approver);
        }
        if (order.secondApprover() != null) {
            throw new BusinessException("撤回单双人批准已完成，禁止重复批准");
        }

        // 第二人批准：最终事务重查全清单（已持撤回单行锁，再按升序锁分装行）
        List<WithdrawalItem> frozenItems = withdrawals.findItems(withdrawalId);
        List<Long> ids = frozenItems.stream().map(WithdrawalItem::dispensingId).sorted().toList();
        List<Dispensing> locked = dispensings.findByIdsForUpdateOrdered(ids);
        if (locked.size() != ids.size()) {
            throw new BusinessException("冻结清单中的分装已不存在，整单全部不撤回");
        }
        for (WithdrawalItem item : frozenItems) {
            Dispensing d = byId(locked, item.dispensingId());
            if (d.activeWithdrawalId() == null || d.activeWithdrawalId() != withdrawalId) {
                throw new BusinessException("分装撤回占用已释放或不属于本撤回单，整单全部不撤回: dispensingId="
                        + d.id());
            }
            if (d.status() == DispensingStatus.DISPENSED) {
                throw new BusinessException("清单中存在已发药分装，整单全部不撤回: dispensingId=" + d.id());
            }
            if (d.status() != DispensingStatus.CALCULATED && d.status() != DispensingStatus.CHECKED) {
                throw new BusinessException("清单中分装已不处于合法未发药状态，整单全部不撤回: dispensingId="
                        + d.id() + " status=" + d.status());
            }
            if (d.versionNo() != item.frozenVersionNo()) {
                throw new BusinessException("清单中分装计算版本已换，整单全部不撤回: dispensingId=" + d.id()
                        + " frozen=" + item.frozenVersionNo() + " actual=" + d.versionNo());
            }
        }

        // 全合法：一起终态化。先结束各分装全部未生效（PENDING）时刻候选，再把分装转 VOID 并使核对失效。
        for (Dispensing d : locked) {
            List<ChangeOrder> pendingCandidates = changeOrders.findByDispensing(d.id()).stream()
                    .filter(c -> c.status() == com.example.starter.radio.domain.ChangeOrderStatus.PENDING)
                    .toList();
            for (ChangeOrder candidate : pendingCandidates) {
                changeOrders.markWithdrawn(candidate.id(), withdrawalId, now);
            }
            dispensings.markVoidForWithdrawal(d.id(), now);
        }
        withdrawals.insertApproval(withdrawalId, approver, 2, now);
        int rows = withdrawals.markApproved(withdrawalId, approver, expectedVersion, now);
        if (rows != 1) {
            // 版本/状态不符：整体回滚，拒绝不能留下部分 VOID
            throw new ConflictException("撤回单最终批准写入失败（版本或状态已变化），全部不撤回: " + withdrawalId);
        }
        return withdrawals.findById(withdrawalId).orElseThrow();
    }

    /**
     * 未批准整体撤销：释放全部分装占用。PENDING 可撤销；已批准不能撤销。幂等：同键同参重放，异参 409。
     */
    @Transactional
    public WithdrawalOrder cancel(String cancelRequestId, long withdrawalId, int expectedVersion) {
        var byCancelKey = withdrawals.findByCancelRequestId(cancelRequestId);
        if (byCancelKey.isPresent()) {
            WithdrawalOrder o = byCancelKey.get();
            if (o.id() != withdrawalId) {
                throw new ConflictException("相同撤销 requestId 引用不同撤回单: " + cancelRequestId);
            }
            return o;
        }

        WithdrawalOrder read = withdrawals.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new NotFoundException("撤回单不存在: " + withdrawalId));
        if (read.status() == WithdrawalStatus.APPROVED) {
            throw new BusinessException("撤回单已批准，不能撤销");
        }
        if (read.status() == WithdrawalStatus.CANCELLED) {
            throw new BusinessException("撤回单已整体撤销，禁止重复撤销");
        }
        if (expectedVersion != read.versionNo()) {
            throw new ConflictException("撤回单版本已变化: expected=" + expectedVersion
                    + " actual=" + read.versionNo());
        }
        long now = clock.instant().getEpochSecond();
        int rows = withdrawals.markCancelled(withdrawalId, cancelRequestId, expectedVersion, now);
        if (rows != 1) {
            throw new ConflictException("撤回单撤销写入失败（版本或状态已变化）: " + withdrawalId);
        }
        List<Long> ids = withdrawals.findItems(withdrawalId).stream()
                .map(WithdrawalItem::dispensingId).sorted().toList();
        dispensings.clearActiveWithdrawal(ids, now);
        return withdrawals.findById(withdrawalId).orElseThrow();
    }

    /**
     * 撤回单证据查询：逐项给出冻结版本、撤回原因，以及被终结候选与双人批准链。
     */
    @Transactional(readOnly = true)
    public WithdrawalEvidence evidence(long withdrawalId) {
        WithdrawalOrder order = withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new NotFoundException("撤回单不存在: " + withdrawalId));
        return new WithdrawalEvidence(order, withdrawals.findItems(withdrawalId),
                withdrawals.findApprovals(withdrawalId),
                changeOrders.findByTerminatedWithdrawal(withdrawalId));
    }

    @Transactional(readOnly = true)
    public WithdrawalOrder getOrder(long withdrawalId) {
        return withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new NotFoundException("撤回单不存在: " + withdrawalId));
    }

    /**
     * 规范化申请项：非空、1 至 10 项、分装不重复；按分装主键升序形成任务集合的规范序。
     */
    private List<RequestItem> normalize(String requestId, List<RequestItem> requestItems) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (requestItems == null || requestItems.size() < MIN_ITEMS || requestItems.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("撤回申请须引用 " + MIN_ITEMS + " 至 " + MAX_ITEMS + " 份分装");
        }
        for (RequestItem item : requestItems) {
            if (item == null || item.dispensingId() <= 0) {
                throw new IllegalArgumentException("撤回清单项缺少合法分装主键");
            }
            if (item.expectedVersion() <= 0) {
                throw new IllegalArgumentException("撤回清单项缺少合法计算版本: dispensingId=" + item.dispensingId());
            }
            if (item.reason() == null || item.reason().isBlank()) {
                throw new IllegalArgumentException("撤回清单项缺少撤回原因: dispensingId=" + item.dispensingId());
            }
        }
        List<RequestItem> sorted = requestItems.stream()
                .sorted(Comparator.comparingLong(RequestItem::dispensingId))
                .toList();
        Set<Long> distinct = new LinkedHashSet<>();
        for (RequestItem item : sorted) {
            if (!distinct.add(item.dispensingId())) {
                throw new IllegalArgumentException("撤回申请包含重复分装: dispensingId=" + item.dispensingId());
            }
        }
        return sorted;
    }

    /**
     * 业务内容指纹（不含时刻）：结构化长度前缀编码，覆盖 requestId、整单原因与任务集合
     * （每项分装主键、当前有效计算版本、逐项原因），集合按分装主键升序规范化。
     */
    private String contentFingerprint(String requestId, String reason, List<RequestItem> items) {
        List<Object> fields = new ArrayList<>();
        fields.add("WITHDRAWAL_SUBMIT");
        fields.add(requestId);
        fields.add(reason == null ? "" : reason);
        fields.add(items.size());
        for (RequestItem item : items) {
            fields.add(item.dispensingId());
            fields.add(item.expectedVersion());
            fields.add(item.reason());
        }
        return Fingerprints.structured(fields.toArray());
    }

    /** 完整指纹：业务内容 + 创建 UTC 精确时刻（epoch 秒）。 */
    private String fullFingerprint(String contentFingerprint, long createdAtEpoch) {
        return Fingerprints.structured("WITHDRAWAL_FULL", contentFingerprint, "CREATED_AT_EPOCH",
                createdAtEpoch);
    }

    private SubmitResult replayOrConflict(WithdrawalOrder existing, String contentFingerprint) {
        if (!existing.contentFingerprint().equals(contentFingerprint)) {
            throw new ConflictException("相同 requestId 但任务集合或原因不一致，拒绝重复受理: "
                    + existing.requestId());
        }
        return new SubmitResult(existing, withdrawals.findItems(existing.id()), true);
    }

    private Dispensing byId(List<Dispensing> locked, long id) {
        return locked.stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }
}
