package com.example.starter.bonded.service;

import com.example.starter.bonded.domain.HistoryPo;
import com.example.starter.bonded.domain.ItemPo;
import com.example.starter.bonded.domain.OrderPo;
import com.example.starter.bonded.domain.TransferStatus;
import com.example.starter.bonded.dto.ApplyTransferRequest;
import com.example.starter.bonded.dto.ItemRequest;
import com.example.starter.bonded.dto.TransferView;
import com.example.starter.bonded.dto.VerifyRequest;
import com.example.starter.bonded.repo.EnterpriseRepository;
import com.example.starter.bonded.repo.HistoryRepository;
import com.example.starter.bonded.repo.IdempotencyRepository;
import com.example.starter.bonded.repo.InventoryRepository;
import com.example.starter.bonded.repo.ItemRepository;
import com.example.starter.bonded.repo.OrderRepository;
import com.example.starter.bonded.repo.WarehouseRepository;
import com.example.starter.bonded.support.Fingerprints;
import com.example.starter.bonded.support.TimeProvider;
import com.example.starter.bonded.web.ApiBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 保税转关核心服务：申请、核注、在途、核销、结案、撤销与超时异常处理。
 * 全部多步写操作均在单事务内完成；并发通过行锁与条件更新保证唯一终态。
 */
@Service
public class TransferService {

    /** 核注后核销时限：48 小时（UTC）。 */
    public static final Duration VERIFY_TTL = Duration.ofHours(48);

    private final OrderRepository orders;
    private final ItemRepository items;
    private final InventoryRepository inventory;
    private final EnterpriseRepository enterprises;
    private final WarehouseRepository warehouses;
    private final HistoryRepository history;
    private final IdempotencyRepository idempotency;
    private final TimeProvider clock;
    private final ObjectMapper objectMapper;
    private final TimeoutGuard timeoutGuard;

    public TransferService(OrderRepository orders, ItemRepository items, InventoryRepository inventory,
                           EnterpriseRepository enterprises, WarehouseRepository warehouses,
                           HistoryRepository history, IdempotencyRepository idempotency,
                           TimeProvider clock, ObjectMapper objectMapper, TimeoutGuard timeoutGuard) {
        this.orders = orders;
        this.items = items;
        this.inventory = inventory;
        this.enterprises = enterprises;
        this.warehouses = warehouses;
        this.history = history;
        this.idempotency = idempotency;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.timeoutGuard = timeoutGuard;
    }

    /**
     * 幂等操作结果：响应报文（JSON）与是否命中首次记录回放。
     */
    public record IdempotentResult(String bodyJson, boolean replayed) {
    }

    // ------------------------------------------------------------------ 申请

    /**
     * 转关申请（幂等）。同 requestId 同参数返回首次结果；同 requestId 异参数返回 409。
     */
    @Transactional
    public IdempotentResult apply(ApplyTransferRequest req) {
        String fingerprint = Fingerprints.ofApply(req);
        var existing = idempotency.find(req.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        validateApply(req);
        Instant now = clock.now();
        List<ItemRequest> merged = Fingerprints.mergeItems(req.items());
        OrderPo po = new OrderPo(null, req.requestId(), req.enterpriseId(), req.fromWarehouse(),
                req.toWarehouse(), TransferStatus.APPLIED, fingerprint, null, null, now, now);
        Long orderId;
        try {
            orderId = orders.insert(po);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已落库：按幂等记录回放或判 409
            return idempotency.find(req.requestId())
                    .map(rec -> replayOrConflict(rec, fingerprint))
                    .orElseThrow(() -> conflict("相同 requestId 的请求正在处理中，请按原参数重试"));
        }
        items.insertAll(orderId, merged.stream()
                .map(i -> new ItemPo(null, orderId, i.hsCode(), i.quantity(), i.unitPrice(), 0L))
                .toList());
        history.append(orderId, null, TransferStatus.APPLIED, now, "转关申请");
        String body = writeJson(viewOf(orderId));
        storeIdempotency(req.requestId(), fingerprint, body, now);
        return new IdempotentResult(body, false);
    }

    private void validateApply(ApplyTransferRequest req) {
        if (req.fromWarehouse().equals(req.toWarehouse())) {
            throw badRequest("起运仓与目的仓不得相同");
        }
        if (!enterprises.exists(req.enterpriseId())) {
            throw badRequest("企业不存在: " + req.enterpriseId());
        }
        if (enterprises.isFrozen(req.enterpriseId())) {
            throw conflict("企业存在超时异常单，已被冻结，新申请拒绝");
        }
        if (!warehouses.exists(req.fromWarehouse())) {
            throw badRequest("起运仓不存在: " + req.fromWarehouse());
        }
        if (!warehouses.exists(req.toWarehouse())) {
            throw badRequest("目的仓不存在: " + req.toWarehouse());
        }
        try {
            Fingerprints.mergeItems(req.items());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 核注

    /**
     * 起运地核注：库存冻结扣减与状态迁移同事务；库存不足或并发冲突时整体回滚。
     */
    @Transactional
    public TransferView endorse(String requestId) {
        OrderPo order = lockOrder(requestId);
        if (order.status() != TransferStatus.APPLIED) {
            throw conflict("当前状态 " + order.status() + " 不允许核注");
        }
        if (enterprises.isFrozen(order.enterpriseId())) {
            throw conflict("企业已被冻结，禁止核注");
        }
        List<ItemPo> lines = items.findByOrderIdForUpdate(order.id());
        for (ItemPo line : lines) {
            if (inventory.freezeIfAvailable(order.fromWarehouse(), line.hsCode(), line.quantity()) == 0) {
                throw conflict("起运仓库存不足，核注失败: " + line.hsCode());
            }
        }
        Instant now = clock.now();
        Instant deadline = now.plus(VERIFY_TTL);
        if (orders.endorseIfApplied(order.id(), TransferStatus.ENDORSED, now, deadline, now) == 0) {
            throw conflict("核注与并发操作冲突，单据状态已变化");
        }
        history.append(order.id(), TransferStatus.APPLIED, TransferStatus.ENDORSED, now,
                "起运地核注，库存冻结扣减，核销截止 " + deadline);
        return viewOf(order.id());
    }

    // ------------------------------------------------------------------ 在途

    /**
     * 起运离港：已核注 -> 在途。超时标记由独立事务先行提交，再进入本事务。
     */
    @Transactional
    public TransferView depart(String requestId) {
        rejectIfAbnormal(requestId);
        OrderPo order = lockOrder(requestId);
        if (order.status() != TransferStatus.ENDORSED) {
            throw conflict("当前状态 " + order.status() + " 不允许离港");
        }
        Instant now = clock.now();
        if (orders.updateStatusIfCurrent(order.id(), TransferStatus.ENDORSED, TransferStatus.IN_TRANSIT, now) == 0) {
            throw conflict("离港与并发操作冲突，单据状态已变化");
        }
        history.append(order.id(), TransferStatus.ENDORSED, TransferStatus.IN_TRANSIT, now, "起运离港，转关在途");
        return viewOf(order.id());
    }

    // ------------------------------------------------------------------ 核销

    /**
     * 目的地分批核销（按 batchRequestId 幂等）。超量整批拒绝；恰好核满即结案。
     * 幂等占位在事务内先行插入：并发同键由唯一约束串行化，业务失败回滚时键可重用。
     */
    @Transactional
    public IdempotentResult verify(String requestId, VerifyRequest req) {
        String fingerprint = Fingerprints.ofVerify(requestId, req);
        var existing = idempotency.find(req.batchRequestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        try {
            idempotency.insertPlaceholder(req.batchRequestId(), fingerprint, clock.now());
        } catch (DuplicateKeyException e) {
            // 并发同键：首个事务已提交，回放其结果或判 409
            return idempotency.find(req.batchRequestId())
                    .map(rec -> replayOrConflict(rec, fingerprint))
                    .orElseThrow(() -> conflict("相同 batchRequestId 的请求正在处理中，请按原参数重试"));
        }
        rejectIfAbnormal(requestId);
        OrderPo order = lockOrder(requestId);
        if (order.status() == TransferStatus.CLOSED) {
            throw conflict("单据已结案，为终态，拒绝新的核销批次");
        }
        if (order.status() != TransferStatus.IN_TRANSIT) {
            throw conflict("当前状态 " + order.status() + " 不允许核销");
        }
        Map<String, ItemPo> lines = items.findByOrderIdForUpdate(order.id()).stream()
                .collect(Collectors.toMap(ItemPo::hsCode, Function.identity()));
        // 同批内同税号先合并，再逐项原子核销；任一超量则整批回滚
        Map<String, Long> batch = req.items().stream()
                .collect(Collectors.groupingBy(i -> i.hsCode(), Collectors.summingLong(i -> i.quantity())));
        for (var e : batch.entrySet()) {
            ItemPo line = lines.get(e.getKey());
            if (line == null) {
                throw badRequest("核销税号不在转关单商品项中: " + e.getKey());
            }
            if (items.addVerifiedIfWithinQuota(line.id(), e.getValue()) == 0) {
                throw conflict("核销数量超过可用余量，整批拒绝: " + e.getKey());
            }
            if (inventory.consumeFrozen(order.fromWarehouse(), e.getKey(), e.getValue()) == 0) {
                throw conflict("起运仓冻结库存不足，核销失败: " + e.getKey());
            }
        }
        Instant now = clock.now();
        boolean fullyVerified = items.findByOrderId(order.id()).stream()
                .allMatch(i -> i.verifiedQty() == i.quantity());
        if (fullyVerified) {
            if (orders.updateStatusIfCurrent(order.id(), TransferStatus.IN_TRANSIT, TransferStatus.CLOSED, now) == 0) {
                throw conflict("结案与并发操作冲突，单据状态已变化");
            }
            history.append(order.id(), TransferStatus.IN_TRANSIT, TransferStatus.CLOSED, now,
                    "批次 " + req.batchRequestId() + " 核销完成，恰好核满结案");
        } else {
            history.append(order.id(), TransferStatus.IN_TRANSIT, TransferStatus.IN_TRANSIT, now,
                    "批次 " + req.batchRequestId() + " 部分核销");
        }
        String body = writeJson(viewOf(order.id()));
        idempotency.updateBody(req.batchRequestId(), body);
        return new IdempotentResult(body, false);
    }

    // ------------------------------------------------------------------ 撤销

    /**
     * 撤销（仅在途前：APPLIED/ENDORSED）。已核注的撤销原子恢复起运仓库存；
     * 与在途核注并发时通过行锁 + 条件更新形成唯一终态。
     */
    @Transactional
    public TransferView cancel(String requestId) {
        OrderPo order = lockOrder(requestId);
        if (order.status() != TransferStatus.APPLIED && order.status() != TransferStatus.ENDORSED) {
            throw conflict("当前状态 " + order.status() + " 不允许撤销");
        }
        Instant now = clock.now();
        if (orders.updateStatusIfCurrent(order.id(), order.status(), TransferStatus.CANCELLED, now) == 0) {
            throw conflict("撤销与并发核注冲突，单据状态已变化");
        }
        if (order.status() == TransferStatus.ENDORSED) {
            for (ItemPo line : items.findByOrderIdForUpdate(order.id())) {
                if (inventory.unfreeze(order.fromWarehouse(), line.hsCode(), line.quantity()) == 0) {
                    throw conflict("恢复起运仓库存失败: " + line.hsCode());
                }
            }
        }
        history.append(order.id(), order.status(), TransferStatus.CANCELLED, now, "转关单撤销，恢复起运仓库存");
        return viewOf(order.id());
    }

    // ------------------------------------------------------------------ 异常与复核

    /**
     * 海关复核确认解除异常：ABNORMAL -> IN_TRANSIT，核销时限自解除时刻顺延 48 小时；
     * 企业无其他异常单时解除冻结。
     */
    @Transactional
    public TransferView resolveAbnormal(String requestId, String reviewer) {
        OrderPo order = lockOrder(requestId);
        if (order.status() != TransferStatus.ABNORMAL) {
            throw conflict("当前状态 " + order.status() + " 不是异常单，无需复核解除");
        }
        Instant now = clock.now();
        Instant newDeadline = now.plus(VERIFY_TTL);
        int updated = orders.resolveAbnormal(order.id(), TransferStatus.IN_TRANSIT, newDeadline, now);
        if (updated == 0) {
            throw conflict("复核解除与并发操作冲突，单据状态已变化");
        }
        history.append(order.id(), TransferStatus.ABNORMAL, TransferStatus.IN_TRANSIT, now,
                "海关复核确认（" + reviewer + "），异常解除，核销截止顺延至 " + newDeadline);
        boolean stillAbnormal = !orders.findByEnterpriseAndStatusIn(
                order.enterpriseId(), List.of(TransferStatus.ABNORMAL)).isEmpty();
        if (!stillAbnormal) {
            enterprises.setFrozen(order.enterpriseId(), false);
        }
        return viewOf(order.id());
    }

    /**
     * 扫描并把恰满 48 小时未核满的单据置为异常：冻结企业及该企业全部在途单。
     * 由定时任务与各业务操作前的惰性检查共同触发。
     *
     * @return 本次置为异常的单据 requestId 列表
     */
    @Transactional
    public List<String> sweepExpired() {
        Instant now = clock.now();
        List<OrderPo> expired = orders.findExpired(now).stream()
                .filter(o -> timeoutGuard.isExpirable(o, now))
                .toList();
        List<String> marked = new java.util.ArrayList<>();
        for (OrderPo o : expired) {
            OrderPo locked = orders.findByIdForUpdate(o.id()).orElse(null);
            if (locked == null) {
                continue;
            }
            if (timeoutGuard.markIfExpirable(locked, now)) {
                marked.add(locked.requestId());
            }
        }
        return marked;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 查询单据视图（含商品项与状态留痕）；查询前执行惰性超时标记（不阻断查询本身）。
     */
    @Transactional
    public TransferView get(String requestId) {
        OrderPo locked = lockOrder(requestId);
        timeoutGuard.markIfExpirable(locked, clock.now());
        return viewOf(locked.id());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 在独立事务中刷新超时状态；若单据已处于（或刚被标记为）异常，则以 409 拒绝后续业务操作。
     * 异常标记已在守卫事务中提交，不受本方法事务回滚影响。
     */
    private void rejectIfAbnormal(String requestId) {
        if (timeoutGuard.refreshAndGetStatus(requestId) == TransferStatus.ABNORMAL) {
            throw conflict("已超过核注后 48 小时核销时限或单据处于异常状态，须海关复核确认后方可继续");
        }
    }

    private OrderPo lockOrder(String requestId) {
        OrderPo order = orders.findByRequestId(requestId)
                .orElseThrow(() -> notFound("转关单不存在: " + requestId));
        return orders.findByIdForUpdate(order.id())
                .orElseThrow(() -> notFound("转关单不存在: " + requestId));
    }

    private IdempotentResult replayOrConflict(IdempotencyRepository.Record rec, String fingerprint) {
        if (!rec.fingerprint().equals(fingerprint)) {
            throw conflict("相同 requestId 但业务参数不一致，幂等冲突");
        }
        return new IdempotentResult(rec.responseBody(), true);
    }

    private void storeIdempotency(String requestId, String fingerprint, String body, Instant now) {
        try {
            idempotency.insertIfAbsent(requestId, fingerprint, body, now);
        } catch (DuplicateKeyException e) {
            // 并发首次写入：以已存在记录为准
        }
    }

    private TransferView viewOf(Long orderId) {
        OrderPo order = orders.findById(orderId)
                .orElseThrow(() -> notFound("转关单不存在: " + orderId));
        List<TransferView.ItemView> itemViews = items.findByOrderId(orderId).stream()
                .map(i -> new TransferView.ItemView(i.hsCode(), i.quantity(), i.unitPrice(), i.verifiedQty()))
                .toList();
        List<TransferView.HistoryView> historyViews = history.findByOrderId(orderId).stream()
                .map(this::toHistoryView)
                .toList();
        return new TransferView(order.requestId(), order.enterpriseId(), order.fromWarehouse(),
                order.toWarehouse(), order.status().name(), order.endorseTime(), order.deadline(),
                enterprises.isFrozen(order.enterpriseId()), itemViews, historyViews);
    }

    private TransferView.HistoryView toHistoryView(HistoryPo h) {
        return new TransferView.HistoryView(
                h.fromStatus() == null ? null : h.fromStatus().name(),
                h.toStatus().name(), h.changedAt(), h.note());
    }

    private String writeJson(TransferView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private ApiBusinessException badRequest(String message) {
        return new ApiBusinessException(HttpStatus.BAD_REQUEST, message);
    }

    private ApiBusinessException conflict(String message) {
        return new ApiBusinessException(HttpStatus.CONFLICT, message);
    }

    private ApiBusinessException notFound(String message) {
        return new ApiBusinessException(HttpStatus.NOT_FOUND, message);
    }
}
