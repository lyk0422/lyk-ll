package com.example.starter.difference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.example.starter.common.ApiException;
import com.example.starter.idempotency.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 购方对账差异单服务：按蓝票行项目登记购方主张金额与合成凭据标识，销方确认或驳回。
 * 规则：单方提交不直接改蓝票金额；驳回解除该行暂停但保留差异记录；
 * 未结差异（PENDING/ESCALATED）只暂停对应行的新增红冲；双方确认后按原红字信息表
 * 流程处理需冲减金额（行金额−购方主张金额），差异单本身不改授信额度；
 * 提交满 72 小时（UTC）无销方答复恰满即升级人工，不自动视为已确认；
 * 与同一行红冲受理竞态时只有一个先占该行：先登记则红冲挂起（409），
 * 先受理/完成红冲则差异单拒绝并保留冲突记录；提交与处置均按 requestId 幂等，
 * 状态与审计记录同事务提交。
 */
@Service
public class DifferenceService {

    static final String ENDPOINT_SUBMIT = "DIFF_SUBMIT";
    static final String ENDPOINT_RESPOND = "DIFF_RESPOND";

    /**
     * 72 小时（UTC）无答复即升级人工处理的时限。
     */
    public static final Duration ESCALATE_AFTER = Duration.ofHours(72);

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DifferenceService(JdbcTemplate jdbc, IdempotencyService idempotencyService,
                             TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记差异单（幂等）。同键同参返回首次结果，同键异参返回 409。
     * 与红冲竞态：该行已有在途红冲申请或已完成红冲时，差异单以 CONFLICT_REJECTED 落库并保留冲突记录。
     */
    public SubmitDifferenceResponse submit(SubmitDifferenceRequest request) {
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

    private SubmitDifferenceResponse doSubmit(SubmitDifferenceRequest request, String fingerprint) {
        idempotencyService.begin(ENDPOINT_SUBMIT, request.requestId(), fingerprint);
        BlueRow blue = lockBlueByNo(request.blueInvoiceNo());
        if (!List.of("DELIVERED", "PARTIAL_RED", "ARCHIVED").contains(blue.status())) {
            throw ApiException.conflict("INVALID_STATE",
                    "蓝票状态 " + blue.status() + " 不允许登记差异（须已交付）");
        }
        LineRow line = findLine(blue.id(), request.lineNo());
        if (request.claimedAmount().compareTo(line.amount()) >= 0) {
            throw ApiException.badRequest("VALIDATION_FAILED",
                    "购方主张金额必须小于行金额 " + line.amount());
        }
        Integer openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM difference_order WHERE blue_line_id = ?"
                        + " AND status IN ('PENDING', 'ESCALATED')",
                Integer.class, line.id());
        if (openCount != null && openCount > 0) {
            throw ApiException.conflict("LINE_DISPUTED", "行 " + request.lineNo() + " 已存在未结差异单");
        }
        // 竞态检查：该行已有在途红冲申请或已完成红冲时，差异单拒绝并保留冲突记录
        boolean completedFlush = line.redFlushedAmount().compareTo(BigDecimal.ZERO) > 0;
        Integer pendingFlush = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_line rl JOIN red_flush_request r ON rl.request_id = r.id"
                        + " WHERE rl.blue_line_id = ? AND r.status = 'PENDING'",
                Integer.class, line.id());
        boolean conflict = completedFlush || (pendingFlush != null && pendingFlush > 0);
        String status = conflict ? "CONFLICT_REJECTED" : "PENDING";
        Instant now = Instant.now();
        long differenceId = insertDifference(blue, line, request, status, now);
        String differenceNo = jdbc.queryForObject(
                "SELECT difference_no FROM difference_order WHERE id = ?", String.class, differenceId);
        appendAudit(differenceId, "SUBMIT", "购方登记差异：主张金额 " + request.claimedAmount()
                + "，凭据 " + request.evidenceRef());
        if (conflict) {
            appendAudit(differenceId, "CONFLICT_REJECT",
                    completedFlush ? "该行已完成红冲，差异单拒绝" : "该行存在在途红冲申请，差异单拒绝");
        }
        SubmitDifferenceResponse response = new SubmitDifferenceResponse(
                conflict ? "CONFLICT_REJECTED" : "CREATED", differenceNo, status, blue.invoiceNo(),
                request.lineNo(), request.claimedAmount());
        idempotencyService.complete(ENDPOINT_SUBMIT, request.requestId(), 200, response);
        return response;
    }

    /**
     * 销方处置（幂等）：确认则按原红字信息表流程创建需冲减金额的红冲申请（PENDING，仍须双方确认）；
     * 驳回则解除该行暂停但保留差异记录。已升级人工或已处置的差异单返回 409。
     */
    public RespondDifferenceResponse respond(String differenceNo, RespondDifferenceRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.action() == null || !List.of("CONFIRM", "REJECT").contains(request.action())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "action 须为 CONFIRM 或 REJECT");
        }
        var fingerprintDto = new RespondFingerprint(differenceNo, request.requestId(), request.action());
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_RESPOND, fingerprintDto);
        var existing = idempotencyService.find(ENDPOINT_RESPOND, request.requestId());
        if (existing.isPresent()) {
            return deserializeRespond(idempotencyService
                    .replayOrReject(ENDPOINT_RESPOND, fingerprintDto, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doRespond(differenceNo, request, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeRespond(idempotencyService
                    .replayOrReject(ENDPOINT_RESPOND, fingerprintDto, request.requestId()).body());
        }
    }

    private RespondDifferenceResponse doRespond(String differenceNo, RespondDifferenceRequest request,
                                                String fingerprint) {
        idempotencyService.begin(ENDPOINT_RESPOND, request.requestId(), fingerprint);
        DifferenceRow row = lockDifferenceByNo(differenceNo);
        switch (row.status()) {
            case "PENDING" -> {
                // 可处置
            }
            case "ESCALATED" -> throw ApiException.conflict("DIFF_ESCALATED",
                    "差异单已升级人工处理，不再接受销方处置: " + differenceNo);
            default -> throw ApiException.conflict("DIFF_ALREADY_RESPONDED",
                    "差异单已处置，当前状态 " + row.status());
        }
        Instant now = Instant.now();
        if ("REJECT".equals(request.action())) {
            // 驳回：解除该行暂停，保留差异记录
            jdbc.update("UPDATE difference_order SET status = 'REJECTED', responded_at = ?,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", java.sql.Timestamp.from(now), row.id());
            appendAudit(row.id(), "REJECT", "销方驳回，解除行暂停，保留差异记录");
            RespondDifferenceResponse response = new RespondDifferenceResponse(
                    differenceNo, "REJECTED", null, null);
            idempotencyService.complete(ENDPOINT_RESPOND, request.requestId(), 200, response);
            return response;
        }
        // 确认：按原红字信息表流程创建需冲减金额（行金额−购方主张金额）的红冲申请
        LineRow line = findLine(row.blueInvoiceId(), row.lineNo());
        BigDecimal flushAmount = line.amount().subtract(row.claimedAmount());
        BigDecimal pendingOnLine = jdbc.queryForObject(
                "SELECT COALESCE(SUM(rl.amount), 0) FROM red_flush_line rl"
                        + " JOIN red_flush_request r ON rl.request_id = r.id"
                        + " WHERE rl.blue_line_id = ? AND r.status = 'PENDING'",
                BigDecimal.class, line.id());
        BigDecimal allowed = line.amount().subtract(line.redFlushedAmount())
                .subtract(pendingOnLine == null ? BigDecimal.ZERO : pendingOnLine);
        if (flushAmount.compareTo(allowed) > 0) {
            throw ApiException.conflict("RED_FLUSH_EXCEEDS_LINE",
                    "需冲减金额 " + flushAmount + " 超出行可红冲余额 " + allowed + "，本次确认回滚");
        }
        long requestId = insertRedFlushRequest(row, flushAmount);
        String redRequestNo = jdbc.queryForObject(
                "SELECT request_no FROM red_flush_request WHERE id = ?", String.class, requestId);
        jdbc.update(
                "INSERT INTO red_flush_line (request_id, blue_line_id, line_no, amount) VALUES (?, ?, ?, ?)",
                requestId, row.blueLineId(), row.lineNo(), flushAmount);
        jdbc.update("UPDATE difference_order SET status = 'CONFIRMED', red_request_no = ?,"
                        + " responded_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                redRequestNo, java.sql.Timestamp.from(now), row.id());
        appendAudit(row.id(), "CONFIRM", "销方确认差异，按原红字信息表流程创建申请 " + redRequestNo
                + "，需冲减金额 " + flushAmount);
        RespondDifferenceResponse response = new RespondDifferenceResponse(
                differenceNo, "CONFIRMED", redRequestNo, flushAmount);
        idempotencyService.complete(ENDPOINT_RESPOND, request.requestId(), 200, response);
        return response;
    }

    /**
     * 升级超时差异单：提交满 72 小时（UTC，恰满即升级）仍无销方答复的 PENDING 单转为 ESCALATED，
     * 不自动视为已确认。状态与审计记录同事务提交，返回升级数量。
     */
    public int escalateOverdue(Instant nowUtc) {
        return transactionTemplate.execute(status -> {
            List<Long> overdueIds = jdbc.query(
                    "SELECT id FROM difference_order WHERE status = 'PENDING' AND submitted_at <= ?"
                            + " FOR UPDATE",
                    (rs, rowNum) -> rs.getLong("id"),
                    java.sql.Timestamp.from(nowUtc.minus(ESCALATE_AFTER)));
            for (Long id : overdueIds) {
                jdbc.update("UPDATE difference_order SET status = 'ESCALATED',"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
                appendAudit(id, "ESCALATE", "提交满72小时（UTC）无销方答复，升级人工处理");
            }
            return overdueIds.size();
        });
    }

    /**
     * 查询差异单详情：状态、需冲减金额与审计轨迹。读取前惰性升级已超时单据，重复查询结果稳定。
     */
    public DifferenceView getDifference(String differenceNo) {
        escalateOverdue(Instant.now());
        DifferenceRow row = findDifferenceByNo(differenceNo);
        List<AuditView> audits = jdbc.query(
                "SELECT action, detail, created_at FROM difference_audit"
                        + " WHERE difference_id = ? ORDER BY id",
                (rs, rowNum) -> new AuditView(rs.getString("action"), rs.getString("detail"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                row.id());
        return new DifferenceView(row.differenceNo(), row.blueInvoiceNo(), row.lineNo(),
                row.claimedAmount(), row.evidenceRef(), row.status(), row.redRequestNo(),
                row.submittedAt(), row.respondedAt(), audits);
    }

    private void validateSubmit(SubmitDifferenceRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.blueInvoiceNo() == null || request.blueInvoiceNo().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "blueInvoiceNo 不能为空");
        }
        if (request.lineNo() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "lineNo 不能为空");
        }
        if (request.claimedAmount() == null || request.claimedAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("VALIDATION_FAILED", "购方主张金额不能为空且不得为负数");
        }
        if (request.evidenceRef() == null || request.evidenceRef().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "合成凭据标识不能为空");
        }
    }

    private BlueRow lockBlueByNo(String invoiceNo) {
        return jdbc.query(
                        "SELECT id, invoice_no, status FROM invoice"
                                + " WHERE invoice_no = ? AND kind = 'BLUE' FOR UPDATE",
                        (rs, rowNum) -> new BlueRow(rs.getLong("id"), rs.getString("invoice_no"),
                                rs.getString("status")), invoiceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "蓝票不存在: " + invoiceNo));
    }

    private LineRow findLine(long blueInvoiceId, int lineNo) {
        return jdbc.query(
                        "SELECT id, line_no, amount, red_flushed_amount FROM invoice_line"
                                + " WHERE invoice_id = ? AND line_no = ?",
                        (rs, rowNum) -> new LineRow(rs.getLong("id"), rs.getInt("line_no"),
                                rs.getBigDecimal("amount"), rs.getBigDecimal("red_flushed_amount")),
                        blueInvoiceId, lineNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("LINE_NOT_FOUND", "蓝票不存在行号: " + lineNo));
    }

    private DifferenceRow lockDifferenceByNo(String differenceNo) {
        return jdbc.query(
                        "SELECT id, difference_no, blue_invoice_id, blue_invoice_no, blue_line_id, line_no,"
                                + " claimed_amount, evidence_ref, status, red_request_no,"
                                + " submitted_at, responded_at FROM difference_order"
                                + " WHERE difference_no = ? FOR UPDATE",
                        (rs, rowNum) -> mapDifference(rs), differenceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("DIFFERENCE_NOT_FOUND", "差异单不存在: " + differenceNo));
    }

    private DifferenceRow findDifferenceByNo(String differenceNo) {
        return jdbc.query(
                        "SELECT id, difference_no, blue_invoice_id, blue_invoice_no, blue_line_id, line_no,"
                                + " claimed_amount, evidence_ref, status, red_request_no,"
                                + " submitted_at, responded_at FROM difference_order"
                                + " WHERE difference_no = ?",
                        (rs, rowNum) -> mapDifference(rs), differenceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("DIFFERENCE_NOT_FOUND", "差异单不存在: " + differenceNo));
    }

    private DifferenceRow mapDifference(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp respondedAt = rs.getTimestamp("responded_at");
        return new DifferenceRow(rs.getLong("id"), rs.getString("difference_no"),
                rs.getLong("blue_invoice_id"), rs.getString("blue_invoice_no"),
                rs.getLong("blue_line_id"), rs.getInt("line_no"),
                rs.getBigDecimal("claimed_amount"), rs.getString("evidence_ref"),
                rs.getString("status"), rs.getString("red_request_no"),
                rs.getTimestamp("submitted_at").toInstant(),
                respondedAt == null ? null : respondedAt.toInstant());
    }

    private long insertDifference(BlueRow blue, LineRow line, SubmitDifferenceRequest request,
                                  String status, Instant submittedAt) {
        String tempNo = "TMP-" + java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO difference_order (difference_no, blue_invoice_id, blue_invoice_no, blue_line_id,"
                        + " line_no, claimed_amount, evidence_ref, status, submitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tempNo, blue.id(), blue.invoiceNo(), line.id(), line.lineNo(), request.claimedAmount(),
                request.evidenceRef(), status, java.sql.Timestamp.from(submittedAt));
        Long id = jdbc.queryForObject("SELECT id FROM difference_order WHERE difference_no = ?",
                Long.class, tempNo);
        String differenceNo = "DIF" + String.format("%08d", id);
        jdbc.update("UPDATE difference_order SET difference_no = ? WHERE id = ?", differenceNo, id);
        return id;
    }

    private long insertRedFlushRequest(DifferenceRow row, BigDecimal flushAmount) {
        String tempNo = "TMP-" + java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO red_flush_request (request_no, blue_invoice_id, blue_invoice_no, flush_type,"
                        + " total_amount, status) VALUES (?, ?, ?, 'PARTIAL', ?, 'PENDING')",
                tempNo, row.blueInvoiceId(), row.blueInvoiceNo(), flushAmount);
        Long id = jdbc.queryForObject("SELECT id FROM red_flush_request WHERE request_no = ?",
                Long.class, tempNo);
        String requestNo = "RFR" + String.format("%08d", id);
        jdbc.update("UPDATE red_flush_request SET request_no = ? WHERE id = ?", requestNo, id);
        return id;
    }

    private void appendAudit(long differenceId, String action, String detail) {
        jdbc.update("INSERT INTO difference_audit (difference_id, action, detail) VALUES (?, ?, ?)",
                differenceId, action, detail);
    }

    private SubmitDifferenceResponse deserializeSubmit(String body) {
        try {
            return objectMapper.readValue(body, SubmitDifferenceResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private RespondDifferenceResponse deserializeRespond(String body) {
        try {
            return objectMapper.readValue(body, RespondDifferenceResponse.class);
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
    record LineRow(long id, int lineNo, BigDecimal amount, BigDecimal redFlushedAmount) {
    }

    /**
     * 差异单内部行视图。
     */
    record DifferenceRow(long id, String differenceNo, long blueInvoiceId, String blueInvoiceNo,
                         long blueLineId, int lineNo, BigDecimal claimedAmount, String evidenceRef,
                         String status, String redRequestNo, Instant submittedAt, Instant respondedAt) {
    }

    /**
     * 差异登记请求（幂等指纹覆盖全部字段）。
     */
    public record SubmitDifferenceRequest(String requestId, String blueInvoiceNo, Integer lineNo,
                                          BigDecimal claimedAmount, String evidenceRef) {
    }

    /**
     * 差异登记响应。result=CONFLICT_REJECTED 时表示与红冲竞态冲突，差异单已落库为拒绝态。
     */
    public record SubmitDifferenceResponse(String result, String differenceNo, String status,
                                           String blueInvoiceNo, Integer lineNo, BigDecimal claimedAmount) {
    }

    /**
     * 销方处置请求（幂等指纹覆盖全部字段及差异单号）。
     */
    public record RespondDifferenceRequest(String requestId, String action) {
    }

    /**
     * 处置幂等指纹载体：requestId + 差异单号 + 动作。
     */
    record RespondFingerprint(String differenceNo, String requestId, String action) {
    }

    /**
     * 销方处置响应。确认时 redRequestNo 为按原红字信息表流程创建的申请编号。
     */
    public record RespondDifferenceResponse(String differenceNo, String status, String redRequestNo,
                                            BigDecimal flushAmount) {
    }

    /**
     * 审计轨迹视图。
     */
    public record AuditView(String action, String detail, java.time.LocalDateTime createdAt) {
    }

    /**
     * 差异单详情视图。
     */
    public record DifferenceView(String differenceNo, String blueInvoiceNo, int lineNo,
                                 BigDecimal claimedAmount, String evidenceRef, String status,
                                 String redRequestNo, Instant submittedAt, Instant respondedAt,
                                 List<AuditView> audits) {
    }
}
