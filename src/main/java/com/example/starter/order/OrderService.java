package com.example.starter.order;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.starter.error.ApiException;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.idem.IdempotencyService.ApiResult;
import com.example.starter.interlock.InterlockService;
import com.example.starter.order.OrderDtos.ConfirmRequest;
import com.example.starter.order.OrderDtos.ConfirmView;
import com.example.starter.order.OrderDtos.DraftRequest;
import com.example.starter.order.OrderDtos.EventView;
import com.example.starter.order.OrderDtos.HitView;
import com.example.starter.order.OrderDtos.IssueRequest;
import com.example.starter.order.OrderDtos.OrderDetail;
import com.example.starter.order.OrderDtos.OrderView;
import com.example.starter.order.OrderDtos.ReviewRequest;
import com.example.starter.order.OrderDtos.StepSpec;
import com.example.starter.order.OrderDtos.StepView;
import com.example.starter.order.OrderDtos.TraceView;
import com.example.starter.order.OrderDtos.UnlockRecordView;
import com.example.starter.order.OrderDtos.UnlockRequest;
import com.example.starter.order.OrderDtos.UnlockView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 倒闸操作票服务：拟票→审核→预发→逐项执行→归档全流程，
 * 含 24 小时有效期、双人确认、防误闭锁、紧急解锁与幂等控制。
 */
@Service
public class OrderService {

    /** 操作票自预发起的有效期。 */
    static final Duration VALIDITY = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final IdempotencyService idem;
    private final InterlockService interlock;
    private final Clock clock;

    public OrderService(JdbcTemplate jdbc, IdempotencyService idem,
                        InterlockService interlock, Clock clock) {
        this.jdbc = jdbc;
        this.idem = idem;
        this.interlock = interlock;
        this.clock = clock;
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, rowNum) -> new OrderRow(
            rs.getLong("id"), rs.getString("order_no"), rs.getString("interval_id"),
            rs.getString("title"), rs.getString("status"), rs.getString("created_by"),
            rs.getString("reviewed_by"),
            rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private record OrderRow(long id, String orderNo, String intervalId, String title,
                            String status, String createdBy, String reviewedBy,
                            Instant issuedAt, Instant createdAt) {
    }

    // ---------- 拟票 ----------

    /**
     * 拟票：requestId 幂等，指纹覆盖 requestId 与全部业务字段。
     */
    @Transactional
    public ResponseEntity<String> draft(DraftRequest req) {
        validateDraft(req);
        String canonical = req.intervalId() + "|" + req.title() + "|" + req.createdBy() + "|"
                + req.steps().stream()
                .map(s -> s.seq() + ":" + s.deviceId() + ":" + s.action() + ":" + s.expectedState())
                .collect(Collectors.joining(","));
        String fingerprint = idem.fingerprint(req.requestId(), canonical);
        return idem.handle(req.requestId(), fingerprint, () -> doDraft(req));
    }

    private ApiResult doDraft(DraftRequest req) {
        Instant now = clock.instant();
        String orderNo = "SO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO switching_order "
                            + "(order_no, interval_id, title, status, created_by, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, orderNo);
            ps.setString(2, req.intervalId());
            ps.setString(3, req.title());
            ps.setString(4, "DRAFT");
            ps.setString(5, req.createdBy());
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long orderId = keyHolder.getKey().longValue();
        for (StepSpec step : req.steps()) {
            jdbc.update("INSERT INTO order_step "
                            + "(order_id, seq, device_id, action, expected_state, status) "
                            + "VALUES (?,?,?,?,?,?)",
                    orderId, step.seq(), step.deviceId(), step.action(),
                    step.expectedState(), "PENDING");
        }
        insertEvent(orderId, "DRAFTED", req.createdBy(), "拟票：" + req.title(), now);
        return new ApiResult(200, new OrderView(orderId, orderNo, req.intervalId(), req.title(),
                "DRAFT", req.createdBy(), null, null, now));
    }

    private void validateDraft(DraftRequest req) {
        if (req.intervalId() == null || req.intervalId().isBlank()
                || req.title() == null || req.title().isBlank()
                || req.createdBy() == null || req.createdBy().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DRAFT", "间隔、标题、拟票人均不能为空");
        }
        if (req.steps() == null || req.steps().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DRAFT", "操作步骤不能为空");
        }
        long distinctSeq = req.steps().stream().mapToInt(StepSpec::seq).distinct().count();
        if (distinctSeq != req.steps().size()
                || req.steps().stream().anyMatch(s -> s.seq() <= 0
                || s.deviceId() == null || s.deviceId().isBlank()
                || s.action() == null || s.action().isBlank()
                || s.expectedState() == null || s.expectedState().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DRAFT",
                    "步序须为正数且票内唯一，设备、操作类型、预期状态均不能为空");
        }
    }

    // ---------- 审核 ----------

    /**
     * 审核：DRAFT → REVIEWED。
     */
    @Transactional
    public OrderView review(long orderId, ReviewRequest req) {
        if (req.reviewedBy() == null || req.reviewedBy().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW", "审核人不能为空");
        }
        OrderRow order = lockOrder(orderId);
        if (!"DRAFT".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "当前状态 " + order.status() + " 不允许审核");
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE switching_order SET status = 'REVIEWED', reviewed_by = ?, "
                + "updated_at = ? WHERE id = ?", req.reviewedBy(), Timestamp.from(now), orderId);
        insertEvent(orderId, "REVIEWED", req.reviewedBy(), "审核通过", now);
        return new OrderView(orderId, order.orderNo(), order.intervalId(), order.title(),
                "REVIEWED", order.createdBy(), req.reviewedBy(), null, order.createdAt());
    }

    // ---------- 预发 ----------

    /**
     * 预发：REVIEWED → EXECUTING，requestId 幂等；
     * 同一间隔同时刻仅允许一张执行中操作票，由唯一约束兜底，并发预发唯一成功。
     */
    @Transactional
    public ResponseEntity<String> issue(long orderId, IssueRequest req) {
        String fingerprint = idem.fingerprint(req.requestId(), "issue|" + orderId);
        return idem.handle(req.requestId(), fingerprint, () -> doIssue(orderId));
    }

    private ApiResult doIssue(long orderId) {
        OrderRow order = lockOrder(orderId);
        if (!"REVIEWED".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "当前状态 " + order.status() + " 不允许预发");
        }
        Instant now = clock.instant();
        try {
            jdbc.update("UPDATE switching_order SET status = 'EXECUTING', issued_at = ?, "
                            + "executing_interval = ?, updated_at = ? WHERE id = ?",
                    Timestamp.from(now), order.intervalId(), Timestamp.from(now), orderId);
        } catch (DuplicateKeyException e) {
            return new ApiResult(409, Map.of("code", "INTERVAL_BUSY",
                    "message", "间隔 " + order.intervalId() + " 已存在执行中操作票"));
        }
        insertEvent(orderId, "ISSUED", null, "预发，24小时有效期起算", now);
        return new ApiResult(200, new OrderView(orderId, order.orderNo(), order.intervalId(),
                order.title(), "EXECUTING", order.createdBy(), order.reviewedBy(),
                now, order.createdAt()));
    }

    // ---------- 逐项执行（步确认） ----------

    /**
     * 步确认：操作人、监护人双确认缺一不可；requestId 幂等；
     * 恰满 24 小时作废且已执行步骤保留；闭锁命中整步拒绝。
     */
    @Transactional
    public ResponseEntity<String> confirm(long orderId, int seq, ConfirmRequest req) {
        String fingerprint = idem.fingerprint(req.requestId(),
                "confirm|" + orderId + "|" + seq + "|" + req.operator() + "|" + req.guardian());
        return idem.handle(req.requestId(), fingerprint, () -> doConfirm(orderId, seq, req));
    }

    private ApiResult doConfirm(long orderId, int seq, ConfirmRequest req) {
        OrderRow order = lockOrder(orderId);
        if (!"EXECUTING".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "当前状态 " + order.status() + " 不允许步确认");
        }
        Instant now = clock.instant();
        if (!now.isBefore(order.issuedAt().plus(VALIDITY))) {
            expireOrder(order, now);
            return new ApiResult(409, Map.of("code", "ORDER_EXPIRED",
                    "message", "操作票自预发起已满 24 小时，作废；已执行步骤保留"));
        }
        StepRow step = findStep(orderId, seq);
        if (step == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STEP_NOT_FOUND",
                    "步骤不存在: seq=" + seq);
        }
        if ("DONE".equals(step.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "STEP_ALREADY_DONE", "步骤已执行");
        }
        Integer pendingBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_step WHERE order_id = ? AND seq < ? AND status = 'PENDING'",
                Integer.class, orderId, seq);
        if (pendingBefore != null && pendingBefore > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STEP_SEQUENCE",
                    "存在未执行的前序步骤，须按步序逐项执行");
        }
        if (req.operator() == null || req.operator().isBlank()
                || req.guardian() == null || req.guardian().isBlank()
                || req.operator().equals(req.guardian())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUAL_CONFIRM_REQUIRED",
                    "每步操作须操作人、监护人双确认且不得为同一人，单人操作不生效");
        }
        var hit = interlock.check(step.deviceId(), step.action());
        if (hit.isPresent()) {
            jdbc.update("INSERT INTO interlock_hit "
                            + "(order_id, step_seq, rule_code, detail, hit_at) VALUES (?,?,?,?,?)",
                    orderId, seq, hit.get().ruleCode(), hit.get().detail(), Timestamp.from(now));
            insertEvent(orderId, "INTERLOCK_HIT", req.operator(),
                    "步" + seq + " 命中闭锁 " + hit.get().ruleCode() + "：" + hit.get().detail(), now);
            return new ApiResult(422, new ConfirmView(orderId, seq, "PENDING", "EXECUTING",
                    hit.get().ruleCode(), "闭锁拒绝：" + hit.get().detail()));
        }
        jdbc.update("UPDATE device_state SET state = ?, updated_at = ? WHERE device_id = ?",
                stateAfter(step.action()), Timestamp.from(now), step.deviceId());
        jdbc.update("UPDATE order_step SET status = 'DONE', operator_name = ?, "
                        + "guardian_name = ?, executed_at = ? WHERE id = ?",
                req.operator(), req.guardian(), Timestamp.from(now), step.id());
        insertEvent(orderId, "STEP_DONE", req.operator() + "/" + req.guardian(),
                "步" + seq + " " + step.action() + " " + step.deviceId() + " 执行完成", now);
        return new ApiResult(200, new ConfirmView(orderId, seq, "DONE", "EXECUTING",
                null, "步骤执行完成"));
    }

    /** 恰满 24 小时作废：状态置 EXPIRED、释放间隔槽位，已执行步骤保留。 */
    private void expireOrder(OrderRow order, Instant now) {
        jdbc.update("UPDATE switching_order SET status = 'EXPIRED', executing_interval = NULL, "
                + "updated_at = ? WHERE id = ?", Timestamp.from(now), order.id());
        insertEvent(order.id(), "EXPIRED", null, "自预发起恰满24小时，操作票作废", now);
    }

    private static String stateAfter(String action) {
        return switch (action) {
            case "OPEN_BREAKER" -> "OFF";
            case "CLOSE_BREAKER" -> "ON";
            case "OPEN_DISCONNECTOR" -> "OPEN";
            case "CLOSE_DISCONNECTOR" -> "CLOSED";
            case "ATTACH_GROUND" -> "ATTACHED";
            case "REMOVE_GROUND" -> "REMOVED";
            case "BUS_OUTAGE" -> "DEENERGIZED";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACTION",
                    "未知操作类型: " + action);
        };
    }

    // ---------- 归档 ----------

    /**
     * 归档：终态。要求全部步骤已执行；重复归档返回幂等成功。
     */
    @Transactional
    public OrderView archive(long orderId) {
        OrderRow order = lockOrder(orderId);
        if ("ARCHIVED".equals(order.status())) {
            return toView(findOrder(orderId));
        }
        if (!"EXECUTING".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "当前状态 " + order.status() + " 不允许归档");
        }
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_step WHERE order_id = ? AND status = 'PENDING'",
                Integer.class, orderId);
        if (pending != null && pending > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STEPS_PENDING",
                    "尚有 " + pending + " 步未执行，不允许归档");
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE switching_order SET status = 'ARCHIVED', executing_interval = NULL, "
                + "updated_at = ? WHERE id = ?", Timestamp.from(now), orderId);
        insertEvent(orderId, "ARCHIVED", null, "归档，操作票进入终态", now);
        return toView(findOrder(orderId));
    }

    // ---------- 紧急解锁 ----------

    /**
     * 紧急解锁：须双人授权并全程留痕；解锁与闭锁恢复在同一事务内完成，
     * 任一步失败整体回滚，不允许只解锁不恢复。
     */
    @Transactional
    public UnlockView unlock(long orderId, UnlockRequest req) {
        OrderRow order = lockOrder(orderId);
        if (req.authorizedBy1() == null || req.authorizedBy1().isBlank()
                || req.authorizedBy2() == null || req.authorizedBy2().isBlank()
                || req.authorizedBy1().equals(req.authorizedBy2())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUAL_AUTH_REQUIRED",
                    "紧急解锁须双人授权且两名授权人不得相同");
        }
        if (req.deviceId() == null || req.deviceId().isBlank()
                || req.reason() == null || req.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UNLOCK", "解锁设备与原因不能为空");
        }
        Instant now = clock.instant();
        int unlocked = jdbc.update(
                "UPDATE device_state SET unlocked = TRUE, updated_at = ? WHERE device_id = ?",
                Timestamp.from(now), req.deviceId());
        if (unlocked == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND",
                    "设备不存在: " + req.deviceId());
        }
        insertEvent(orderId, "UNLOCK", req.authorizedBy1() + "&" + req.authorizedBy2(),
                "紧急解锁 " + req.deviceId() + "：" + req.reason(), now);
        int restored = jdbc.update(
                "UPDATE device_state SET unlocked = FALSE, updated_at = ? WHERE device_id = ?",
                Timestamp.from(now), req.deviceId());
        if (restored == 0) {
            throw new IllegalStateException("闭锁恢复失败，整体回滚: " + req.deviceId());
        }
        insertEvent(orderId, "INTERLOCK_RESTORED", req.authorizedBy1() + "&" + req.authorizedBy2(),
                "闭锁恢复 " + req.deviceId(), now);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement(
                        "INSERT INTO unlock_record (order_id, device_id, authorized_by1, "
                                + "authorized_by2, reason, unlocked_at, restored_at) "
                                + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, orderId);
                ps.setString(2, req.deviceId());
                ps.setString(3, req.authorizedBy1());
                ps.setString(4, req.authorizedBy2());
                ps.setString(5, req.reason());
                ps.setTimestamp(6, Timestamp.from(now));
                ps.setTimestamp(7, Timestamp.from(now));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "UNLOCK_EXISTS",
                    "该设备在本票下已有解锁记录");
        }
        return new UnlockView(keyHolder.getKey().longValue(), orderId, req.deviceId(), now, now);
    }

    // ---------- 查询 ----------

    /**
     * 操作票详情：含全部步骤。
     */
    @Transactional(readOnly = true)
    public OrderDetail get(long orderId) {
        OrderRow order = findOrder(orderId);
        List<StepView> steps = jdbc.query(
                "SELECT seq, device_id, action, expected_state, status, operator_name, "
                        + "guardian_name, executed_at FROM order_step WHERE order_id = ? ORDER BY seq",
                (rs, rowNum) -> new StepView(rs.getInt("seq"), rs.getString("device_id"),
                        rs.getString("action"), rs.getString("expected_state"),
                        rs.getString("status"), rs.getString("operator_name"),
                        rs.getString("guardian_name"),
                        rs.getTimestamp("executed_at") == null ? null
                                : rs.getTimestamp("executed_at").toInstant()),
                orderId);
        return new OrderDetail(toView(order), steps);
    }

    /**
     * 轨迹：流转事件、闭锁命中与解锁记录，均可复算。
     */
    @Transactional(readOnly = true)
    public TraceView trace(long orderId) {
        findOrder(orderId);
        List<EventView> events = jdbc.query(
                "SELECT event_type, actor, detail, event_at FROM order_event "
                        + "WHERE order_id = ? ORDER BY id",
                (rs, rowNum) -> new EventView(rs.getString("event_type"), rs.getString("actor"),
                        rs.getString("detail"), rs.getTimestamp("event_at").toInstant()),
                orderId);
        List<HitView> hits = jdbc.query(
                "SELECT step_seq, rule_code, detail, hit_at FROM interlock_hit "
                        + "WHERE order_id = ? ORDER BY id",
                (rs, rowNum) -> new HitView(rs.getInt("step_seq"), rs.getString("rule_code"),
                        rs.getString("detail"), rs.getTimestamp("hit_at").toInstant()),
                orderId);
        List<UnlockRecordView> unlocks = jdbc.query(
                "SELECT device_id, authorized_by1, authorized_by2, reason, unlocked_at, "
                        + "restored_at FROM unlock_record WHERE order_id = ? ORDER BY id",
                (rs, rowNum) -> new UnlockRecordView(rs.getString("device_id"),
                        rs.getString("authorized_by1"), rs.getString("authorized_by2"),
                        rs.getString("reason"), rs.getTimestamp("unlocked_at").toInstant(),
                        rs.getTimestamp("restored_at").toInstant()),
                orderId);
        return new TraceView(events, hits, unlocks);
    }

    // ---------- 内部工具 ----------

    private OrderRow findOrder(long orderId) {
        List<OrderRow> rows = jdbc.query(
                "SELECT id, order_no, interval_id, title, status, created_by, reviewed_by, "
                        + "issued_at, created_at FROM switching_order WHERE id = ?",
                ORDER_MAPPER, orderId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                    "操作票不存在: " + orderId);
        }
        return rows.get(0);
    }

    private OrderRow lockOrder(long orderId) {
        List<OrderRow> rows = jdbc.query(
                "SELECT id, order_no, interval_id, title, status, created_by, reviewed_by, "
                        + "issued_at, created_at FROM switching_order WHERE id = ? FOR UPDATE",
                ORDER_MAPPER, orderId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                    "操作票不存在: " + orderId);
        }
        return rows.get(0);
    }

    private StepRow findStep(long orderId, int seq) {
        List<StepRow> rows = jdbc.query(
                "SELECT id, device_id, action, status FROM order_step "
                        + "WHERE order_id = ? AND seq = ?",
                (rs, rowNum) -> new StepRow(rs.getLong("id"), rs.getString("device_id"),
                        rs.getString("action"), rs.getString("status")),
                orderId, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertEvent(long orderId, String type, String actor, String detail, Instant at) {
        jdbc.update("INSERT INTO order_event (order_id, event_type, actor, detail, event_at) "
                + "VALUES (?,?,?,?,?)", orderId, type, actor, detail, Timestamp.from(at));
    }

    private OrderView toView(OrderRow order) {
        return new OrderView(order.id(), order.orderNo(), order.intervalId(), order.title(),
                order.status(), order.createdBy(), order.reviewedBy(), order.issuedAt(),
                order.createdAt());
    }

    private record StepRow(long id, String deviceId, String action, String status) {
    }
}
