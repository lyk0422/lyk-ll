package com.example.starter.transfer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.transfer.TransferDtos.ApplyRequest;
import com.example.starter.transfer.TransferDtos.HistoryEntry;
import com.example.starter.transfer.TransferDtos.ItemView;
import com.example.starter.transfer.TransferDtos.OrderDetailResponse;
import com.example.starter.transfer.TransferDtos.OrderResponse;
import com.example.starter.transfer.TransferDtos.ReleaseRequest;
import com.example.starter.transfer.TransferDtos.ReleaseResponse;
import com.example.starter.transfer.TransferDtos.StatusChangeResponse;
import com.example.starter.transfer.TransferDtos.VerifyItemRequest;
import com.example.starter.transfer.TransferDtos.VerifyRequest;
import com.example.starter.transfer.TransferDtos.VerifyResponse;
import com.example.starter.transfer.TransferRepository.ItemRow;
import com.example.starter.transfer.TransferRepository.OrderRow;
import com.example.starter.transfer.TransferRepository.VerificationRow;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 保税转关核心业务：申请 → 起运地核注 → 在途 → 目的地核销 → 结案。
 * 核注与库存冻结扣减同事务；核注后 48 小时（UTC）内核销，恰满 48 小时未核销自动转异常；
 * 申请与核销均按 requestId 幂等；结案为终态。
 */
@Service
public class TransferService {

    /** 核销时限：核注后 48 小时。 */
    static final Duration VERIFY_WINDOW = Duration.ofHours(48);

    private final TransferRepository repo;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TransferService(TransferRepository repo, Clock clock, ObjectMapper objectMapper) {
        this.repo = repo;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ---------- 申请 ----------

    /**
     * 申请转关单。按 requestId 幂等：同键同参返回首次结果，同键异参返回 409。
     * 企业存在未解除异常时拒绝新申请。
     */
    @Transactional
    public OrderResponse apply(ApplyRequest req) {
        String fingerprint = Fingerprints.ofApply(req);
        var existing = repo.findOrderByRequestId(req.requestId());
        if (existing.isPresent()) {
            return replayApply(existing.get(), fingerprint);
        }
        if (repo.isEnterpriseFrozen(req.enterpriseId())) {
            throw ApiException.conflict("企业存在未解除的超时异常，新申请被拒绝: " + req.enterpriseId());
        }
        Instant now = clock.instant();
        OrderRow row = new OrderRow(0L, req.requestId(), fingerprint, req.enterpriseId(),
                req.fromWarehouse(), req.toWarehouse(), TransferStatus.APPLIED, 0, null, null, now);
        try {
            repo.insertOrder(row);
        } catch (DuplicateKeyException e) {
            // 并发同键申请：以唯一约束兜底，重放首次结果或报异参冲突
            return repo.findOrderByRequestId(req.requestId())
                    .map(r -> replayApply(r, fingerprint))
                    .orElseThrow(() -> e);
        }
        OrderRow saved = repo.findOrderByRequestId(req.requestId()).orElseThrow();
        for (var item : Fingerprints.sortedByTariff(req.items(), i -> i.tariffCode())) {
            repo.insertItem(saved.id(), item.tariffCode(), item.quantity(), item.unitPrice());
        }
        repo.insertHistory(saved.id(), 0, null, TransferStatus.APPLIED, now, "转关申请");
        return toResponse(repo.findOrderById(saved.id()).orElseThrow());
    }

    private OrderResponse replayApply(OrderRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("相同 requestId 已存在且业务参数不一致: " + row.requestId());
        }
        return toResponse(row);
    }

    // ---------- 核注 ----------

    /**
     * 起运地核注：核注与起运仓库存冻结扣减在同一事务内完成；
     * 与撤销并发时通过行级锁形成唯一终态。
     */
    @Transactional
    public StatusChangeResponse endorse(long orderId) {
        OrderRow order = lockOrder(orderId);
        if (order.status() != TransferStatus.APPLIED) {
            throw ApiException.conflict("当前状态不允许核注: " + order.status());
        }
        List<ItemRow> items = repo.findItems(orderId);
        // 先按税号排序逐行锁定并校验库存，全部满足后再统一扣减，保证原子性
        List<ItemRow> sorted = items.stream()
                .sorted(java.util.Comparator.comparing(ItemRow::tariffCode)).toList();
        for (ItemRow item : sorted) {
            long available = repo.lockStock(order.fromWarehouse(), item.tariffCode())
                    .orElseThrow(() -> ApiException.conflict(
                            "起运仓库存不存在: " + order.fromWarehouse() + "/" + item.tariffCode()));
            if (available < item.quantity()) {
                throw ApiException.conflict("起运仓库存不足: " + item.tariffCode()
                        + " 可用 " + available + " 需扣减 " + item.quantity());
            }
        }
        for (ItemRow item : sorted) {
            repo.decreaseStock(order.fromWarehouse(), item.tariffCode(), item.quantity());
        }
        Instant now = clock.instant();
        Instant deadline = now.plus(VERIFY_WINDOW);
        repo.updateOrderStatus(orderId, TransferStatus.IN_TRANSIT, now, deadline, now);
        repo.insertHistory(orderId, order.version() + 1, TransferStatus.APPLIED,
                TransferStatus.IN_TRANSIT, now, "起运地核注，起运仓库存冻结扣减");
        return new StatusChangeResponse(orderId, TransferStatus.IN_TRANSIT.name());
    }

    // ---------- 撤销 ----------

    /**
     * 在途前撤销：原子恢复起运仓库存（核注前撤销时无已扣减库存，恢复为零操作）。
     * 与核注并发时由行级锁保证唯一终态。
     */
    @Transactional
    public StatusChangeResponse cancel(long orderId) {
        OrderRow order = lockOrder(orderId);
        if (order.status() != TransferStatus.APPLIED) {
            throw ApiException.conflict("仅在途前可撤销，当前状态: " + order.status());
        }
        // 防御性恢复：若存在已核注扣减的库存则原子恢复
        if (order.endorsedAt() != null) {
            for (ItemRow item : repo.findItems(orderId)) {
                repo.increaseStock(order.fromWarehouse(), item.tariffCode(), item.quantity());
            }
        }
        Instant now = clock.instant();
        repo.updateOrderStatus(orderId, TransferStatus.CANCELLED, order.endorsedAt(),
                order.verifyDeadline(), now);
        repo.insertHistory(orderId, order.version() + 1, TransferStatus.APPLIED,
                TransferStatus.CANCELLED, now, "在途前撤销，恢复起运仓库存");
        return new StatusChangeResponse(orderId, TransferStatus.CANCELLED.name());
    }

    // ---------- 核销 ----------

    /**
     * 目的地核销：按 requestId 幂等；允许分批部分核销，累计不得超过核注数量，
     * 超量整批拒绝；恰好核满即结案，结案为终态。
     */
    @Transactional
    public VerifyResponse verify(long orderId, VerifyRequest req) {
        String fingerprint = Fingerprints.ofVerify(orderId, req);
        var existing = repo.findVerification(req.requestId());
        if (existing.isPresent()) {
            return replayVerify(existing.get(), fingerprint);
        }
        OrderRow order = lockOrder(orderId);
        // 行锁后复查，串行化并发同键核销
        existing = repo.findVerification(req.requestId());
        if (existing.isPresent()) {
            return replayVerify(existing.get(), fingerprint);
        }
        // 超时惰性拒绝：恰满 48 小时未核销即拒绝核销；状态迁移由扫描事务提交，避免随本事务回滚
        if (order.status() == TransferStatus.IN_TRANSIT && order.verifyDeadline() != null
                && !clock.instant().isBefore(order.verifyDeadline())) {
            throw ApiException.conflict("已超过 48 小时核销时限，拒绝核销: " + orderId);
        }
        if (order.status() == TransferStatus.CLOSED) {
            throw ApiException.conflict("单据已结案，为终态，拒绝新的核销请求: " + orderId);
        }
        if (order.status() != TransferStatus.IN_TRANSIT) {
            throw ApiException.conflict("当前状态不允许核销: " + order.status());
        }
        List<ItemRow> items = repo.findItems(orderId);
        Map<String, ItemRow> byTariff = items.stream()
                .collect(Collectors.toMap(ItemRow::tariffCode, Function.identity()));
        // 同批内同税号合并后校验，任一商品项超量则整批拒绝
        Map<String, Long> requested = new TreeMap<>();
        for (VerifyItemRequest item : req.items()) {
            requested.merge(item.tariffCode(), item.quantity(), Long::sum);
        }
        for (Map.Entry<String, Long> entry : requested.entrySet()) {
            ItemRow item = byTariff.get(entry.getKey());
            if (item == null) {
                throw ApiException.conflict("核销商品项不存在于转关单: " + entry.getKey());
            }
            long remaining = item.quantity() - item.verifiedQuantity();
            if (entry.getValue() > remaining) {
                throw ApiException.conflict("核销数量超过可用余量: " + entry.getKey()
                        + " 余量 " + remaining + " 请求 " + entry.getValue());
            }
        }
        for (Map.Entry<String, Long> entry : requested.entrySet()) {
            repo.addVerifiedQuantity(byTariff.get(entry.getKey()).id(), entry.getValue());
        }
        List<ItemRow> updated = repo.findItems(orderId);
        boolean closed = updated.stream().allMatch(i -> i.verifiedQuantity() == i.quantity());
        Instant now = clock.instant();
        TransferStatus status = order.status();
        if (closed) {
            repo.updateOrderStatus(orderId, TransferStatus.CLOSED, order.endorsedAt(),
                    order.verifyDeadline(), now);
            repo.insertHistory(orderId, order.version() + 1, TransferStatus.IN_TRANSIT,
                    TransferStatus.CLOSED, now, "累计核销数量达到核注数量，结案");
            status = TransferStatus.CLOSED;
        }
        VerifyResponse response = new VerifyResponse(req.requestId(), orderId, status.name(),
                closed, toItemViews(updated));
        String body = toJson(response);
        try {
            repo.insertVerification(req.requestId(), fingerprint, orderId, body, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("核销 requestId 已被其他单据使用: " + req.requestId());
        }
        return response;
    }

    private VerifyResponse replayVerify(VerificationRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("相同 requestId 已存在且核销参数不一致: " + row.requestId());
        }
        try {
            return objectMapper.readValue(row.responseBody(), VerifyResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("核销结果快照反序列化失败", e);
        }
    }

    // ---------- 超时扫描 ----------

    /**
     * 扫描恰满 48 小时未核销的在途单：转异常、冻结企业全部在途单、拒绝企业新申请。
     * 由定时任务触发，也可在测试中用可控时钟手动触发。
     *
     * @return 本次转异常的单据 id 列表
     */
    @Transactional
    public List<Long> scanExpired() {
        Instant now = clock.instant();
        List<Long> expired = new ArrayList<>();
        for (OrderRow candidate : repo.findExpiredInTransit(now)) {
            OrderRow locked = lockOrder(candidate.id());
            if (locked.status() != TransferStatus.IN_TRANSIT || locked.verifyDeadline() == null
                    || now.isBefore(locked.verifyDeadline())) {
                continue;
            }
            expireOrderAndFreezeEnterprise(locked);
            expired.add(locked.id());
        }
        return expired;
    }

    private void expireOrderAndFreezeEnterprise(OrderRow order) {
        Instant now = clock.instant();
        repo.updateOrderStatus(order.id(), TransferStatus.EXCEPTION, order.endorsedAt(),
                order.verifyDeadline(), now);
        repo.insertHistory(order.id(), order.version() + 1, TransferStatus.IN_TRANSIT,
                TransferStatus.EXCEPTION, now, "恰满 48 小时未核销，自动转异常");
        repo.setEnterpriseFrozen(order.enterpriseId(), true, now);
        for (OrderRow candidate : repo.findByEnterpriseAndStatus(
                order.enterpriseId(), TransferStatus.IN_TRANSIT)) {
            OrderRow locked = lockOrder(candidate.id());
            if (locked.status() != TransferStatus.IN_TRANSIT) {
                continue;
            }
            repo.updateOrderStatus(locked.id(), TransferStatus.FROZEN, locked.endorsedAt(),
                    locked.verifyDeadline(), now);
            repo.insertHistory(locked.id(), locked.version() + 1, TransferStatus.IN_TRANSIT,
                    TransferStatus.FROZEN, now, "企业存在超时异常，在途单冻结");
        }
    }

    // ---------- 海关复核解除异常 ----------

    /**
     * 海关复核确认后解除企业异常：企业解冻，冻结单恢复在途；
     * 超时异常单恢复在途并重置 48 小时核销时限（自解除时刻起算）。
     */
    @Transactional
    public ReleaseResponse releaseEnterprise(String enterpriseId, ReleaseRequest req) {
        if (!repo.isEnterpriseFrozen(enterpriseId)) {
            throw ApiException.conflict("企业无待解除的异常: " + enterpriseId);
        }
        Instant now = clock.instant();
        repo.setEnterpriseFrozen(enterpriseId, false, now);
        List<Long> restored = new ArrayList<>();
        for (TransferStatus status : List.of(TransferStatus.FROZEN, TransferStatus.EXCEPTION)) {
            for (OrderRow candidate : repo.findByEnterpriseAndStatus(enterpriseId, status)) {
                OrderRow locked = lockOrder(candidate.id());
                if (locked.status() != status) {
                    continue;
                }
                Instant deadline = status == TransferStatus.EXCEPTION
                        ? now.plus(VERIFY_WINDOW) : locked.verifyDeadline();
                String note = status == TransferStatus.EXCEPTION
                        ? "海关复核解除异常(" + req.reviewer() + ")，核销时限重置为解除后 48 小时"
                        : "海关复核解除异常(" + req.reviewer() + ")，恢复在途";
                repo.updateOrderStatus(locked.id(), TransferStatus.IN_TRANSIT,
                        locked.endorsedAt(), deadline, now);
                repo.insertHistory(locked.id(), locked.version() + 1, status,
                        TransferStatus.IN_TRANSIT, now, note);
                restored.add(locked.id());
            }
        }
        return new ReleaseResponse(enterpriseId, false, restored);
    }

    // ---------- 查询 ----------

    /** 单据详情：单据视图与按序状态留痕。 */
    @Transactional(readOnly = true)
    public OrderDetailResponse getDetail(long orderId) {
        OrderRow order = repo.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("转关单不存在: " + orderId));
        List<HistoryEntry> history = repo.findHistory(orderId);
        return new OrderDetailResponse(toResponse(order), history);
    }

    // ---------- 内部辅助 ----------

    private OrderRow lockOrder(long orderId) {
        return repo.lockOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("转关单不存在: " + orderId));
    }

    private OrderResponse toResponse(OrderRow row) {
        return new OrderResponse(row.id(), row.requestId(), row.enterpriseId(),
                row.fromWarehouse(), row.toWarehouse(), row.status().name(),
                row.endorsedAt(), row.verifyDeadline(), row.createdAt(),
                toItemViews(repo.findItems(row.id())));
    }

    private static List<ItemView> toItemViews(List<ItemRow> items) {
        return items.stream()
                .map(i -> new ItemView(i.tariffCode(), i.quantity(), i.unitPrice(), i.verifiedQuantity()))
                .toList();
    }

    private String toJson(VerifyResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("核销结果快照序列化失败", e);
        }
    }
}
