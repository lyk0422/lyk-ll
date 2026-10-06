package com.example.starter.discrepancy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.common.ApiException;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.redflush.RedFlushService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 购方对账差异单服务：按蓝票行项目登记购方主张金额与合成凭据，销方确认或驳回。
 * 规则：单方提交不改蓝票金额与额度；未结差异（OPEN/ESCALATED）只暂停对应行的新增红冲；
 * 驳回解除该行暂停但保留差异记录；双方确认差异后按原红字信息表流程处理需冲减金额，
 * 只有红票确认生效才恢复授信额度；提交满 72 小时（UTC）未答复恰满即升级人工，不自动视为确认；
 * 与同一行红冲受理并发时只能一方先占该行，红冲在途/已完成则差异拒绝并保留冲突记录；
 * 提交与处置均按 requestId 幂等，同键同参重放首次结果，同键异参 409，
 * 状态与审计记录同事务提交。
 */
@Service
public class DiscrepancyService {

    static final String ENDPOINT_SUBMIT = "DISCREPANCY_SUBMIT";
    static final String ENDPOINT_RESOLVE = "DISCREPANCY_RESOLVE";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final DiscrepancyGuard guard;
    private final RedFlushService redFlushService;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DiscrepancyService(JdbcTemplate jdbc, Clock clock, DiscrepancyGuard guard,
                              RedFlushService redFlushService, IdempotencyService idempotencyService,
                              TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.guard = guard;
        this.redFlushService = redFlushService;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交差异单（幂等）。同一行红冲在途或已完成时拒绝（CONFLICT_REJECTED）并保留冲突记录。
     */
    public SubmitDiscrepancyResponse submit(SubmitDiscrepancyRequest request) {
        validateSubmit(request);
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_SUBMIT, request);
        var existing = idempotencyService.find(ENDPOINT_SUBMIT, request.requestId());
        if (existing.isPresent()) {
            return deserializeSubmit(
                    idempotencyService.replayOrReject(ENDPOINT_SUBMIT, request, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doSubmit(request, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发同键：首次请求已提交，重放其结果
            return deserializeSubmit(
                    idempotencyService.replayOrReject(ENDPOINT_SUBMIT, request, request.requestId()).body());
        }
    }

    private SubmitDiscrepancyResponse doSubmit(SubmitDiscrepancyRequest request, String fingerprint) {
        idempotencyService.begin(ENDPOINT_SUBMIT, request.requestId(), fingerprint);
        // 先锁蓝票行（与红冲受理同一锁序：发票→差异），保证并发时只有一方先占该行
        BlueRow blue = lockBlueByNo(request.blueInvoiceNo());
        if (!List.of("DELIVERED", "PARTIAL_RED", "ARCHIVED").contains(blue.status())) {
            throw ApiException.conflict("INVALID_STATE",
                    "蓝票状态 " + blue.status() + " 不允许登记差异（须已交付或已归档）");
        }
        guard.escalateExpired(blue.id());
        LineRow line = findLine(blue.id(), request.lineNo());
        BigDecimal diff = line.amount().subtract(request.claimedAmount());
        if (diff.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("VALIDATION_FAILED",
                    "主张金额必须小于行金额，否则无需冲减差异");
        }
        // 冲突检测：该行（含覆盖该行的全额申请）红冲在途或已完成 → 差异拒绝并保留冲突记录
        String conflictReason = conflictReason(blue.id(), line.id());
        if (conflictReason != null) {
            jdbc.update(
                    "INSERT INTO discrepancy_conflict (blue_invoice_no, line_no, claimed_amount,"
                            + " evidence_ref, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    blue.invoiceNo(), request.lineNo(), request.claimedAmount(), request.evidenceRef(),
                    conflictReason, Timestamp.from(clock.instant()));
            SubmitDiscrepancyResponse response = new SubmitDiscrepancyResponse(
                    "CONFLICT_REJECTED", null, null, blue.invoiceNo(), request.lineNo(), diff);
            idempotencyService.complete(ENDPOINT_SUBMIT, request.requestId(), 200, response);
            return response;
        }
        if (guard.isLineBlocked(blue.id(), request.lineNo())) {
            throw ApiException.conflict("DISCREPANCY_EXISTS",
                    "行 " + request.lineNo() + " 已存在未结差异单");
        }
        long id = insertDiscrepancy(blue, line, request, diff);
        String discrepancyNo = jdbc.queryForObject(
                "SELECT discrepancy_no FROM discrepancy WHERE id = ?", String.class, id);
        guard.insertEvent(id, "SUBMITTED");
        SubmitDiscrepancyResponse response = new SubmitDiscrepancyResponse(
                "SUBMITTED", discrepancyNo, "OPEN", blue.invoiceNo(), request.lineNo(), diff);
        idempotencyService.complete(ENDPOINT_SUBMIT, request.requestId(), 200, response);
        return response;
    }

    /**
     * 销方处置差异单（幂等）：CONFIRM 确认 / REJECT 驳回。
     * 确认后按原红字信息表流程为需冲减金额创建 PENDING 申请，差异单本身不改额度；
     * 驳回解除该行暂停但保留差异记录；已升级人工的差异拒绝处置。
     */
    public ResolveDiscrepancyResponse resolve(String discrepancyNo, ResolveDiscrepancyRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.action() == null || !List.of("CONFIRM", "REJECT").contains(request.action())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "action 须为 CONFIRM 或 REJECT");
        }
        ResolveCommand command = new ResolveCommand(request.requestId(), discrepancyNo, request.action());
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_RESOLVE, command);
        var existing = idempotencyService.find(ENDPOINT_RESOLVE, request.requestId());
        if (existing.isPresent()) {
            return deserializeResolve(
                    idempotencyService.replayOrReject(ENDPOINT_RESOLVE, command, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doResolve(command, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeResolve(
                    idempotencyService.replayOrReject(ENDPOINT_RESOLVE, command, request.requestId()).body());
        }
    }

    private ResolveDiscrepancyResponse doResolve(ResolveCommand command, String fingerprint) {
        idempotencyService.begin(ENDPOINT_RESOLVE, command.requestId(), fingerprint);
        // 锁序与红冲受理一致：先锁发票行，再锁差异单行，避免并发死锁
        DiscrepancyRow probe = findByNo(command.discrepancyNo());
        lockBlueByNo(probe.blueInvoiceNo());
        DiscrepancyRow row = lockByNo(command.discrepancyNo());
        // 恰满 72 小时未答复即升级人工，升级后不再接受销方处置
        if ("OPEN".equals(row.status())) {
            guard.escalateIfExpired(row.id());
            row = lockByNo(command.discrepancyNo());
        }
        switch (row.status()) {
            case "ESCALATED" -> throw ApiException.conflict("DISCREPANCY_ESCALATED",
                    "差异单已满 72 小时未答复，升级人工处理: " + command.discrepancyNo());
            case "CONFIRMED", "REJECTED" -> throw ApiException.conflict("DISCREPANCY_ALREADY_RESOLVED",
                    "差异单已处置: " + command.discrepancyNo());
            default -> {
                // OPEN：继续处置
            }
        }
        Timestamp now = Timestamp.from(clock.instant());
        String redRequestNo = null;
        String newStatus;
        if ("CONFIRM".equals(command.action())) {
            newStatus = "CONFIRMED";
            // 双方确认差异后，按原红字信息表流程处理需冲减金额；差异单本身不改额度
            redRequestNo = redFlushService.createSystemRequest(
                    row.blueInvoiceNo(), row.lineNo(), row.diffAmount());
        } else {
            newStatus = "REJECTED";
        }
        jdbc.update("UPDATE discrepancy SET status = ?, responded_at = ?, red_request_no = ?"
                        + " WHERE id = ?",
                newStatus, now, redRequestNo, row.id());
        guard.insertEvent(row.id(), newStatus);
        ResolveDiscrepancyResponse response = new ResolveDiscrepancyResponse(
                command.discrepancyNo(), newStatus, redRequestNo);
        idempotencyService.complete(ENDPOINT_RESOLVE, command.requestId(), 200, response);
        return response;
    }

    /**
     * 查询差异单：状态、需冲减金额、关联红字信息表与审计轨迹；满 72 小时未答复的
     * 在查询时恰满即升级（同事务落库），重复查询结果稳定。
     */
    public DiscrepancyView getDiscrepancy(String discrepancyNo) {
        return transactionTemplate.execute(status -> {
            DiscrepancyRow row = findByNo(discrepancyNo);
            if ("OPEN".equals(row.status())) {
                guard.escalateIfExpired(row.id());
                row = findByNo(discrepancyNo);
            }
            List<DiscrepancyEventView> events = jdbc.query(
                    "SELECT event, created_at FROM discrepancy_event WHERE discrepancy_id = ? ORDER BY id",
                    (rs, rowNum) -> new DiscrepancyEventView(rs.getString("event"),
                            rs.getTimestamp("created_at").toLocalDateTime()),
                    row.id());
            return new DiscrepancyView(row.discrepancyNo(), row.blueInvoiceNo(), row.lineNo(),
                    row.claimedAmount(), row.diffAmount(), row.evidenceRef(), row.status(),
                    row.redRequestNo(), row.createdAt().toLocalDateTime(),
                    row.respondedAt() == null ? null : row.respondedAt().toLocalDateTime(), events);
        });
    }

    private void validateSubmit(SubmitDiscrepancyRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.blueInvoiceNo() == null || request.blueInvoiceNo().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "blueInvoiceNo 不能为空");
        }
        if (request.lineNo() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "lineNo 不能为空");
        }
        if (request.evidenceRef() == null || request.evidenceRef().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "evidenceRef 不能为空");
        }
        if (request.claimedAmount() == null || request.claimedAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("VALIDATION_FAILED", "主张金额不能为空且不得为负数");
        }
    }

    /**
     * 该行红冲冲突原因：存在覆盖该行的在途（PENDING）或已完成（ISSUED）红冲申请时
     * 返回对应原因，否则返回 null。全额申请覆盖所有行。
     */
    private String conflictReason(long blueInvoiceId, long blueLineId) {
        Integer issued = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_request r"
                        + " WHERE r.blue_invoice_id = ? AND r.status = 'ISSUED'"
                        + " AND (r.flush_type = 'FULL' OR EXISTS (SELECT 1 FROM red_flush_line rl"
                        + "   WHERE rl.request_id = r.id AND rl.blue_line_id = ?))",
                Integer.class, blueInvoiceId, blueLineId);
        if (issued != null && issued > 0) {
            return "RED_FLUSH_COMPLETED";
        }
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_request r"
                        + " WHERE r.blue_invoice_id = ? AND r.status = 'PENDING'"
                        + " AND (r.flush_type = 'FULL' OR EXISTS (SELECT 1 FROM red_flush_line rl"
                        + "   WHERE rl.request_id = r.id AND rl.blue_line_id = ?))",
                Integer.class, blueInvoiceId, blueLineId);
        if (pending != null && pending > 0) {
            return "RED_FLUSH_IN_PROGRESS";
        }
        return null;
    }

    private long insertDiscrepancy(BlueRow blue, LineRow line, SubmitDiscrepancyRequest request,
                                   BigDecimal diff) {
        String tempNo = "TMP-" + java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO discrepancy (discrepancy_no, blue_invoice_id, blue_invoice_no, blue_line_id,"
                        + " line_no, claimed_amount, diff_amount, evidence_ref, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)",
                tempNo, blue.id(), blue.invoiceNo(), line.id(), request.lineNo(), request.claimedAmount(),
                diff, request.evidenceRef(), Timestamp.from(clock.instant()));
        Long id = jdbc.queryForObject("SELECT id FROM discrepancy WHERE discrepancy_no = ?",
                Long.class, tempNo);
        String discrepancyNo = "DIF" + String.format("%08d", id);
        jdbc.update("UPDATE discrepancy SET discrepancy_no = ? WHERE id = ?", discrepancyNo, id);
        return id;
    }

    private BlueRow lockBlueByNo(String invoiceNo) {
        return jdbc.query(
                        "SELECT id, invoice_no, status FROM invoice"
                                + " WHERE invoice_no = ? AND kind = 'BLUE' FOR UPDATE",
                        (rs, rowNum) -> new BlueRow(rs.getLong("id"), rs.getString("invoice_no"),
                                rs.getString("status")),
                        invoiceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "蓝票不存在: " + invoiceNo));
    }

    private LineRow findLine(long blueInvoiceId, int lineNo) {
        return jdbc.query(
                        "SELECT id, amount FROM invoice_line WHERE invoice_id = ? AND line_no = ?",
                        (rs, rowNum) -> new LineRow(rs.getLong("id"), rs.getBigDecimal("amount")),
                        blueInvoiceId, lineNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("LINE_NOT_FOUND", "蓝票不存在行号: " + lineNo));
    }

    private DiscrepancyRow lockByNo(String discrepancyNo) {
        return jdbc.query(
                        "SELECT id, discrepancy_no, blue_invoice_no, line_no, claimed_amount, diff_amount,"
                                + " evidence_ref, status, red_request_no, created_at, responded_at"
                                + " FROM discrepancy WHERE discrepancy_no = ? FOR UPDATE",
                        (rs, rowNum) -> mapRow(rs), discrepancyNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("DISCREPANCY_NOT_FOUND", "差异单不存在: " + discrepancyNo));
    }

    private DiscrepancyRow findByNo(String discrepancyNo) {
        return jdbc.query(
                        "SELECT id, discrepancy_no, blue_invoice_no, line_no, claimed_amount, diff_amount,"
                                + " evidence_ref, status, red_request_no, created_at, responded_at"
                                + " FROM discrepancy WHERE discrepancy_no = ?",
                        (rs, rowNum) -> mapRow(rs), discrepancyNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("DISCREPANCY_NOT_FOUND", "差异单不存在: " + discrepancyNo));
    }

    private DiscrepancyRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DiscrepancyRow(rs.getLong("id"), rs.getString("discrepancy_no"),
                rs.getString("blue_invoice_no"), rs.getInt("line_no"),
                rs.getBigDecimal("claimed_amount"), rs.getBigDecimal("diff_amount"),
                rs.getString("evidence_ref"), rs.getString("status"), rs.getString("red_request_no"),
                rs.getTimestamp("created_at"), rs.getTimestamp("responded_at"));
    }

    private SubmitDiscrepancyResponse deserializeSubmit(String body) {
        try {
            return objectMapper.readValue(body, SubmitDiscrepancyResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ResolveDiscrepancyResponse deserializeResolve(String body) {
        try {
            return objectMapper.readValue(body, ResolveDiscrepancyResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 蓝票内部行视图。
     */
    record BlueRow(long id, String invoiceNo, String status) {
    }

    /**
     * 蓝票行内部视图。
     */
    record LineRow(long id, BigDecimal amount) {
    }

    /**
     * 差异单内部行视图。
     */
    record DiscrepancyRow(long id, String discrepancyNo, String blueInvoiceNo, int lineNo,
                          BigDecimal claimedAmount, BigDecimal diffAmount, String evidenceRef,
                          String status, String redRequestNo, Timestamp createdAt, Timestamp respondedAt) {
    }

    /**
     * 差异提交请求（幂等指纹覆盖全部字段）。
     */
    public record SubmitDiscrepancyRequest(String requestId, String blueInvoiceNo, Integer lineNo,
                                           BigDecimal claimedAmount, String evidenceRef) {
    }

    /**
     * 差异提交响应。result=CONFLICT_REJECTED 时该行红冲在途或已完成，差异拒绝且冲突记录已保留。
     */
    public record SubmitDiscrepancyResponse(String result, String discrepancyNo, String status,
                                            String blueInvoiceNo, Integer lineNo, BigDecimal diffAmount) {
    }

    /**
     * 差异处置请求。
     */
    public record ResolveDiscrepancyRequest(String requestId, String action) {
    }

    /**
     * 差异处置命令（幂等指纹覆盖 requestId、差异单号与处置动作）。
     */
    record ResolveCommand(String requestId, String discrepancyNo, String action) {
    }

    /**
     * 差异处置响应。status=CONFIRMED 时 redRequestNo 为按原红字信息表流程创建的申请编号。
     */
    public record ResolveDiscrepancyResponse(String discrepancyNo, String status, String redRequestNo) {
    }

    /**
     * 差异单审计事件视图。
     */
    public record DiscrepancyEventView(String event, LocalDateTime createdAt) {
    }

    /**
     * 差异单详情视图。createdAt/respondedAt 为 UTC 时间。
     */
    public record DiscrepancyView(String discrepancyNo, String blueInvoiceNo, int lineNo,
                                  BigDecimal claimedAmount, BigDecimal diffAmount, String evidenceRef,
                                  String status, String redRequestNo, LocalDateTime createdAt,
                                  LocalDateTime respondedAt, List<DiscrepancyEventView> events) {
    }
}
