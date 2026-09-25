package com.example.starter.dispatch.service;

import com.example.starter.dispatch.api.ApiException;
import com.example.starter.dispatch.api.dto.GateTarget;
import com.example.starter.dispatch.api.dto.IssueOrderRequest;
import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.api.dto.ReceiptRequest;
import com.example.starter.dispatch.domain.OrderStatus;
import com.example.starter.dispatch.repo.AlarmRow;
import com.example.starter.dispatch.repo.OrderRepository;
import com.example.starter.dispatch.repo.OrderRow;
import com.example.starter.dispatch.repo.ReceiptRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 闸门调度令领域服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>下达/签收/回执均以 requestId 幂等：指纹覆盖 requestId 与全部业务字段，
 *       同键同参返回首次结果，同键异参 409 且不改状态；</li>
 *   <li>同一闸门同一时刻只允许一张未完成令（gate_occupancy 唯一索引兜底并发）；</li>
 *   <li>多闸门按令中顺序依次执行，前一门未回执不得启动下一门；</li>
 *   <li>回执偏差 |实际-目标|/目标 ≤ ±2%（含边界）判到位，超限自动告警；</li>
 *   <li>签收前可撤销（终态），已签收只能中止（记录已执行闸门实际开度）；</li>
 *   <li>超时升级与最后一门回执竞争时，以行锁 + 条件更新 + 时限复核形成唯一终态。</li>
 * </ul>
 */
@Service
public class DispatchService {

    /** 到位判定阈值：相对目标开度的 2%。 */
    private static final BigDecimal POSITION_TOLERANCE_RATIO = new BigDecimal("0.02");
    /** 目标开度为 0 时退化为按满量程 2 个百分点判定。 */
    private static final BigDecimal ZERO_TARGET_TOLERANCE = new BigDecimal("2.000");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OrderRepository repo;
    private final Clock clock;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public DispatchService(OrderRepository repo, Clock clock,
                           org.springframework.transaction.PlatformTransactionManager txManager,
                           ObjectMapper objectMapper) {
        this.repo = repo;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
    }

    // ---------------- 下达 ----------------

    /**
     * 下达调度令。采用“先查重、独立短事务插入、唯一索引兜底”的方式：
     * 并发同 requestId 或并发占用同一闸门时，数据库唯一约束保证只有一方生效。
     */
    public OrderView issue(IssueOrderRequest request) {
        validateIssueRequest(request);
        Instant now = Instant.now(clock);

        OrderRow existing = repo.findByRequestId(request.requestId());
        if (existing != null) {
            return replayOrConflict(existing, request);
        }

        try {
            Long orderId = txTemplate.execute(status -> insertNewOrder(request, now));
            if (orderId == null) {
                throw new IllegalStateException("调度令插入返回空主键");
            }
        } catch (DuplicateKeyException e) {
            // 并发同 requestId 或并发闸门占用：事务已回滚，重新读取区分。
            OrderRow concurrent = repo.findByRequestId(request.requestId());
            if (concurrent != null) {
                return replayOrConflict(concurrent, request);
            }
            List<String> occupied = repo.findOccupiedGates(
                    request.gates().stream().map(GateTarget::gateCode).sorted().toList());
            throw ApiException.conflict("GATE_OCCUPIED",
                    "闸门存在未完成调度令，不能重复下达: " + occupied);
        }
        return buildView(requireOrder(request.requestId()));
    }

    private long insertNewOrder(IssueOrderRequest request, Instant now) {
        List<String> codes = request.gates().stream().map(GateTarget::gateCode).toList();
        String gateCodes = String.join(",", codes);
        String openings = request.gates().stream()
                .map(g -> normalize(g.targetOpening())).reduce((a, b) -> a + "," + b).orElseThrow();
        long orderId = repo.insertOrder(request.requestId(), fingerprint(request),
                gateCodes, openings, request.deadline(), now);
        // 占用插入在令插入之后：闸门唯一索引冲突会回滚整个事务，不会残留令。
        repo.insertOccupancyRows(orderId, codes, now);
        for (int i = 0; i < request.gates().size(); i++) {
            GateTarget gate = request.gates().get(i);
            repo.insertReceipt(orderId, i, gate.gateCode(), gate.targetOpening());
        }
        return orderId;
    }

    private OrderView replayOrConflict(OrderRow existing, IssueOrderRequest request) {
        if (!existing.fingerprint().equals(fingerprint(request))) {
            throw ApiException.conflict("IDEMPOTENT_PARAM_MISMATCH",
                    "requestId=" + request.requestId() + " 已存在但业务参数不一致");
        }
        return buildView(existing);
    }

    // ---------------- 签收 ----------------

    @Transactional
    public OrderView accept(String requestId) {
        long orderId = requireOrder(requestId).id();
        Instant now = Instant.now(clock);
        OrderRow locked = repo.findByIdForUpdate(orderId);
        return switch (locked.status()) {
            case ISSUED -> {
                int updated = repo.markAccepted(orderId, now);
                if (updated == 0) {
                    throw ApiException.conflict("ORDER_STATE_CHANGED", "调度令状态已被并发改变");
                }
                yield buildView(requireOrder(requestId));
            }
            // 已签收（含其后的完成/中止）重复签收：幂等返回首次结果。
            case ACCEPTED, COMPLETED, ABORTED -> buildView(locked);
            case CANCELLED -> throw ApiException.conflict("ORDER_CANCELLED", "调度令已撤销，不可签收");
            case TIMEOUT -> throw ApiException.conflict("ORDER_TIMEOUT", "调度令已超时升级，不可签收");
        };
    }

    // ---------------- 逐门回执 ----------------

    // noRollbackFor：在回执处理中发现已超时限时，先在本事务内完成超时升级（终态+通知+释放闸门），
    // 再以 409 拒绝该回执；升级必须落库提交，不能随业务异常回滚。
    @Transactional(noRollbackFor = ApiException.class)
    public OrderView receipt(String requestId, ReceiptRequest request) {
        validateOpening(request.actualOpening());
        OrderRow order = requireOrder(requestId);
        Instant now = Instant.now(clock);

        // 行锁串行化回执与超时扫描/撤销/中止之间的竞争。
        OrderRow locked = repo.findByIdForUpdate(order.id());
        List<ReceiptRow> receipts = repo.findReceipts(order.id());
        ReceiptRow target = receipts.stream()
                .filter(r -> r.gateCode().equals(request.gateCode()))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("GATE_NOT_IN_ORDER",
                        "闸门 " + request.gateCode() + " 不在调度令 " + requestId + " 中"));

        switch (locked.status()) {
            case CANCELLED -> throw ApiException.conflict("ORDER_CANCELLED", "调度令已撤销，不再接收回执");
            case TIMEOUT -> throw ApiException.conflict("ORDER_TIMEOUT", "调度令已超时升级，回执被拒绝");
            case ABORTED -> throw ApiException.conflict("ORDER_ABORTED", "调度令已人工中止，回执被拒绝");
            case ISSUED -> throw ApiException.conflict("ORDER_NOT_ACCEPTED", "调度令尚未签收，不能回执");
            case COMPLETED -> {
                // 令已完成：仅允许同键同参重放，异参 409 且不改状态。
                ensureSameActual(target, request);
                return buildView(locked);
            }
            case ACCEPTED -> { /* 继续处理 */ }
        }

        // 已回执过的闸门：幂等重放或异参冲突。
        if (target.actualOpening() != null) {
            ensureSameActual(target, request);
            return buildView(locked);
        }

        // 顺序执行约束：前序闸门必须全部已回执完成。
        for (ReceiptRow earlier : receipts) {
            if (earlier.seqNo() < target.seqNo() && earlier.actualOpening() == null) {
                throw ApiException.conflict("GATE_ORDER_VIOLATION",
                        "前序闸门 " + earlier.gateCode() + " 未回执完成，不得执行 " + request.gateCode());
            }
        }

        // 时限复核（在行锁内）：到达时刻严格晚于时限则超时升级，回执不落任何状态。
        if (now.isAfter(locked.deadline())) {
            escalateUnderLock(locked, now,
                    countPending(receipts));
            throw ApiException.conflict("ORDER_TIMEOUT",
                    "已超过执行时限 " + locked.deadline() + "，调度令超时升级，回执被拒绝");
        }

        BigDecimal actual = request.actualOpening().setScale(3, RoundingMode.HALF_UP);
        BigDecimal deviation = deviation(target.targetOpening(), actual);
        BigDecimal tolerance = tolerance(target.targetOpening());
        boolean inPosition = actual.subtract(target.targetOpening()).abs().compareTo(tolerance) <= 0;

        int filled = repo.fillReceiptIfAbsent(target.id(), actual, inPosition, deviation,
                !inPosition, now);
        if (filled == 0) {
            // 并发下已被同闸门回执抢先（理论上行锁内不会发生，防御性处理）。
            throw ApiException.conflict("RECEIPT_EXISTS", "闸门回执已存在");
        }
        repo.updateGateOpening(request.gateCode(), actual, now);
        if (!inPosition) {
            repo.insertAlarm(order.id(), "GATE_DEVIATION", request.gateCode(),
                    "闸门 " + request.gateCode() + " 实际开度 " + actual.toPlainString()
                            + "% 与目标 " + target.targetOpening().toPlainString()
                            + "% 偏差 " + deviation.toPlainString() + "%，超过±2%，自动告警",
                    now);
        }

        List<ReceiptRow> refreshed = repo.findReceipts(order.id());
        if (countPending(refreshed) == 0) {
            int completed = repo.markCompleted(order.id(), now);
            if (completed == 0) {
                // 理论上行锁内不会发生（扫描器拿不到行锁）；若发生说明状态被并发改变，
                // 抛普通运行时异常回滚本事务，避免在 TIMEOUT 令上残留本回执。
                throw new IllegalStateException("最后一门回执落库后令已非 ACCEPTED，回滚回执: " + requestId);
            }
            // 正常完成也是终态：释放全部闸门占用
            repo.deleteOccupanciesByOrder(order.id());
        }
        return buildView(requireOrder(requestId));
    }

    private void ensureSameActual(ReceiptRow existing, ReceiptRequest request) {
        BigDecimal actual = request.actualOpening().setScale(3, RoundingMode.HALF_UP);
        if (existing.actualOpening() != null && existing.actualOpening().compareTo(actual) != 0) {
            throw ApiException.conflict("IDEMPOTENT_PARAM_MISMATCH",
                    "闸门 " + request.gateCode() + " 已回执实际开度 "
                            + existing.actualOpening().toPlainString() + "%，与本次参数不一致");
        }
    }

    // ---------------- 撤销 / 中止 ----------------

    @Transactional
    public OrderView cancel(String requestId) {
        OrderRow order = requireOrder(requestId);
        Instant now = Instant.now(clock);
        OrderRow locked = repo.findByIdForUpdate(order.id());
        switch (locked.status()) {
            case ISSUED -> {
                int updated = repo.markCancelled(order.id(), now);
                if (updated == 0) {
                    throw ApiException.conflict("ORDER_STATE_CHANGED", "调度令状态已被并发改变");
                }
                // 终态释放闸门占用。
                repo.deleteOccupanciesByOrder(order.id());
                return buildView(requireOrder(requestId));
            }
            case CANCELLED -> {
                return buildView(locked); // 撤销幂等
            }
            case ACCEPTED, COMPLETED, ABORTED -> throw ApiException.conflict("ALREADY_ACCEPTED",
                    "调度令已签收，不可撤销，只能中止");
            case TIMEOUT -> throw ApiException.conflict("ORDER_TIMEOUT", "调度令已超时升级，不可撤销");
        }
        throw new IllegalStateException("未覆盖的状态: " + locked.status());
    }

    @Transactional
    public OrderView abort(String requestId) {
        OrderRow order = requireOrder(requestId);
        Instant now = Instant.now(clock);
        OrderRow locked = repo.findByIdForUpdate(order.id());
        switch (locked.status()) {
            case ACCEPTED -> {
                List<ReceiptRow> receipts = repo.findReceipts(order.id());
                String detail = buildAbortedDetail(receipts, now);
                int updated = repo.markAborted(order.id(), now, detail);
                if (updated == 0) {
                    throw ApiException.conflict("ORDER_STATE_CHANGED", "调度令状态已被并发改变");
                }
                repo.deleteOccupanciesByOrder(order.id());
                return buildView(requireOrder(requestId));
            }
            case ABORTED -> {
                return buildView(locked); // 中止幂等，返回首次留痕
            }
            case ISSUED -> throw ApiException.conflict("ORDER_NOT_ACCEPTED",
                    "调度令尚未签收，签收前请使用撤销");
            case CANCELLED -> throw ApiException.conflict("ORDER_CANCELLED", "调度令已撤销，不可中止");
            case COMPLETED -> throw ApiException.conflict("ORDER_COMPLETED", "调度令已完成，不可中止");
            case TIMEOUT -> throw ApiException.conflict("ORDER_TIMEOUT", "调度令已超时升级，不可中止");
        }
        throw new IllegalStateException("未覆盖的状态: " + locked.status());
    }

    /** 中止留痕：记录中止时刻已执行（已回执）闸门的实际开度与完成时刻。 */
    private String buildAbortedDetail(List<ReceiptRow> receipts, Instant now) {
        List<Map<String, Object>> executed = new ArrayList<>();
        for (ReceiptRow r : receipts) {
            if (r.actualOpening() != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("seqNo", r.seqNo());
                item.put("gateCode", r.gateCode());
                item.put("targetOpening", r.targetOpening());
                item.put("actualOpening", r.actualOpening());
                item.put("inPosition", r.inPosition());
                item.put("receivedAt", r.receivedAt() == null ? null : r.receivedAt().toString());
                executed.add(item);
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("executedGates", executed);
        detail.put("abortedAt", now.toString());
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("中止留痕序列化失败", e);
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public OrderView get(String requestId) {
        return buildView(requireOrder(requestId));
    }

    @Transactional(readOnly = true)
    public List<OrderView> list() {
        // 按令主键（即下达顺序）返回，重复查询结果稳定。
        return repo.findAllOrderById().stream().map(this::buildView).toList();
    }

    // ---------------- 超时升级 ----------------

    @Transactional
    public List<OrderView> scanTimeouts() {
        Instant now = Instant.now(clock);
        List<OrderRow> expired = repo.findExpired(now);
        List<OrderView> escalated = new ArrayList<>();
        for (OrderRow candidate : expired) {
            OrderRow locked = repo.findByIdForUpdate(candidate.id());
            if (locked == null || locked.status().isTerminal()) {
                continue; // 并发中已被最后一门回执完成/撤销/中止
            }
            if (!now.isAfter(locked.deadline())) {
                continue; // 扫描起点之后时限才到的边界保护；恰在时限时刻不判超时
            }
            int pending = repo.countPendingReceipts(locked.id());
            escalateUnderLock(locked, now, pending);
            escalated.add(buildView(requireOrder(locked.requestId())));
        }
        return escalated;
    }

    /** 调用方必须已持有令行锁；执行条件更新、通知上级留痕与闸门释放。 */
    private void escalateUnderLock(OrderRow locked, Instant now, int pendingReceipts) {
        int updated = repo.markTimeout(locked.id(), now);
        if (updated == 1) {
            repo.insertAlarm(locked.id(), "TIMEOUT_ESCALATION", null,
                    "调度令 " + locked.requestId() + " 超过执行时限 " + locked.deadline()
                            + " 仍有 " + pendingReceipts + " 孔闸门未回执，已立即升级通知上级并可人工接管",
                    now);
            repo.deleteOccupanciesByOrder(locked.id());
        }
    }

    // ---------------- 视图组装 / 校验 / 指纹 ----------------

    private OrderView buildView(OrderRow order) {
        List<ReceiptRow> receipts = repo.findReceipts(order.id());
        List<OrderView.GateReceiptView> gateViews = receipts.stream()
                .map(r -> new OrderView.GateReceiptView(r.seqNo(), r.gateCode(),
                        r.targetOpening(), r.actualOpening(), r.inPosition(),
                        r.deviationPct(), r.alarmed(), r.receivedAt()))
                .toList();
        List<OrderView.AlarmView> alarmViews = repo.findAlarms(order.id()).stream()
                .map(this::toAlarmView)
                .toList();
        return new OrderView(order.requestId(), order.status(), gateViews, alarmViews,
                order.deadline(), order.issuedAt(), order.acceptedAt(), order.completedAt(),
                order.timeoutAt(), order.cancelledAt(), order.abortedAt(),
                order.escalated(), order.abortedDetail());
    }

    private OrderView.AlarmView toAlarmView(AlarmRow row) {
        return new OrderView.AlarmView(row.alarmType(), row.gateCode(), row.detail(), row.createdAt());
    }

    private OrderRow requireOrder(String requestId) {
        OrderRow order = repo.findByRequestId(requestId);
        if (order == null) {
            throw ApiException.notFound("ORDER_NOT_FOUND", "调度令不存在: " + requestId);
        }
        return order;
    }

    private int countPending(List<ReceiptRow> receipts) {
        return (int) receipts.stream().filter(r -> r.actualOpening() == null).count();
    }

    private void validateIssueRequest(IssueOrderRequest request) {
        List<String> codes = new ArrayList<>();
        for (GateTarget gate : request.gates()) {
            validateOpening(gate.targetOpening());
            if (gate.gateCode() == null || gate.gateCode().isBlank()) {
                throw ApiException.unprocessable("INVALID_GATE", "闸门编码不能为空");
            }
            if (codes.contains(gate.gateCode())) {
                throw ApiException.unprocessable("DUPLICATE_GATE",
                        "同一调度令中闸门不能重复: " + gate.gateCode());
            }
            if (!repo.gateExists(gate.gateCode())) {
                throw ApiException.unprocessable("GATE_NOT_FOUND", "闸门不存在: " + gate.gateCode());
            }
            codes.add(gate.gateCode());
        }
    }

    private void validateOpening(BigDecimal opening) {
        if (opening == null || opening.signum() < 0 || opening.compareTo(HUNDRED) > 0) {
            throw ApiException.unprocessable("INVALID_OPENING", "开度必须在 0~100 之间（含边界，单位%）");
        }
    }

    /** 偏差百分比 =（实际-目标）/目标×100；目标为 0 时改以百分点差值表示。 */
    private BigDecimal deviation(BigDecimal target, BigDecimal actual) {
        BigDecimal diff = actual.subtract(target);
        if (target.signum() == 0) {
            return diff.setScale(4, RoundingMode.HALF_UP);
        }
        return diff.multiply(HUNDRED).divide(target, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal tolerance(BigDecimal target) {
        if (target.signum() == 0) {
            return ZERO_TARGET_TOLERANCE;
        }
        return target.multiply(POSITION_TOLERANCE_RATIO).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 业务指纹：SHA-256，覆盖 requestId、闸门序列（编码+目标开度，顺序敏感）与执行时限。
     * 开度统一保留 3 位小数、时限取 epochMilli，保证同参指纹稳定。
     */
    private String fingerprint(IssueOrderRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("requestId=").append(request.requestId()).append(';');
        canonical.append("gates=[");
        for (int i = 0; i < request.gates().size(); i++) {
            GateTarget g = request.gates().get(i);
            if (i > 0) {
                canonical.append(',');
            }
            canonical.append(i).append(':').append(g.gateCode())
                    .append('=').append(normalize(g.targetOpening()));
        }
        canonical.append("];deadline=").append(request.deadline().toEpochMilli());
        return sha256(canonical.toString());
    }

    private String normalize(BigDecimal opening) {
        return opening.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
