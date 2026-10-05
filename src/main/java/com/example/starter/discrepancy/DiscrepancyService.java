package com.example.starter.discrepancy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * 规则：单方提交不改蓝票金额；未结差异（PENDING/ESCALATED）只暂停对应行的新增红冲；
 * 驳回解除该行暂停但保留差异记录；双方确认后按原红字信息表流程处理需冲减金额，
 * 只有红票确认生效才恢复授信额度，差异单本身不改额度；提交满 72 小时（UTC 恰满即升级）
 * 无销方答复即升级人工处理，不自动视为已确认；差异登记与同一行红冲受理通过蓝票行级
 * 悲观锁串行，先登记则红冲暂停，红冲先占该行（在途占满或已完成）则差异单拒绝并保留
 * 冲突记录；提交与处置按 requestId 幂等，状态与审计记录同事务提交。
 */
@Service
public class DiscrepancyService {

    static final String ENDPOINT_SUBMIT = "DISCREPANCY_SUBMIT";
    static final String ENDPOINT_RESPOND = "DISCREPANCY_RESPOND";

    /**
     * 72 小时（UTC）无答复升级阈值。
     */
    static final Duration ESCALATION_AFTER = Duration.ofHours(72);

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotencyService;
    private final RedFlushService redFlushService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DiscrepancyService(JdbcTemplate jdbc, IdempotencyService idempotencyService,
                              RedFlushService redFlushService, TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.idempotencyService = idempotencyService;
        this.redFlushService = redFlushService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 提交差异单（幂等）。同键同参返回首次结果，同键异参返回 409。
     * 红冲先占该行（在途申请占满或已完成红冲）时拒绝并保留冲突记录，返回 CONFLICT_REJECTED。
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
        // 与红冲受理同一把蓝票行级锁：差异登记与红冲受理只能有一个先占该行
        BlueRow blue = lockBlueByNo(request.blueInvoiceNo());
        if (!List.of("DELIVERED", "PARTIAL_RED", "ARCHIVED", "RED_FLUSHED").contains(blue.status())) {
            throw ApiException.conflict("INVALID_STATE",
                    "蓝票状态 " + blue.status() + " 不允许登记差异（须已交付）");
        }
        LineRow line = findLine(blue.id(), request.lineNo());
        Integer openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_sheet"
                        + " WHERE blue_invoice_id = ? AND line_no = ? AND status IN ('PENDING', 'ESCALATED')",
                Integer.class, blue.id(), request.lineNo());
        if (openCount != null && openCount > 0) {
            throw ApiException.conflict("DISCREPANCY_EXISTS",
                    "行 " + request.lineNo() + " 已存在未结差异单");
        }
        if (request.claimedAmount().compareTo(line.amount()) >= 0) {
            throw ApiException.badRequest("VALIDATION_FAILED",
                    "购方主张金额须小于行金额 " + line.amount() + "，否则无需冲减");
        }
        BigDecimal flushAmount = line.amount().subtract(request.claimedAmount());
        BigDecimal available = effectiveLineAvailable(blue, line);
        Instant now = clock.instant();
        if (flushAmount.compareTo(available) > 0) {
            // 红冲先占该行（在途申请占满或已完成红冲）：差异单拒绝并保留冲突记录，不产生红冲、不改额度
            long id = insertDiscrepancy(blue, line, request, flushAmount, "CONFLICT_REJECTED", now);
            String discrepancyNo = discrepancyNoOf(id);
            insertAudit(id, "CONFLICT_REJECT",
                    "红冲先占该行：需冲减 " + flushAmount + " 超出可申请 " + available + "，差异单拒绝", now);
            SubmitDiscrepancyResponse response = new SubmitDiscrepancyResponse(
                    "CONFLICT_REJECTED", discrepancyNo, "CONFLICT_REJECTED",
                    blue.invoiceNo(), request.lineNo(), flushAmount);
            idempotencyService.complete(ENDPOINT_SUBMIT, request.requestId(), 200, response);
            return response;
        }
        long id = insertDiscrepancy(blue, line, request, flushAmount, "PENDING", now);
        String discrepancyNo = discrepancyNoOf(id);
        insertAudit(id, "SUBMIT", "购方登记差异：主张金额 " + request.claimedAmount()
                + "，需冲减 " + flushAmount + "，凭据 " + request.evidenceRef(), now);
        SubmitDiscrepancyResponse response = new SubmitDiscrepancyResponse(
                "SUBMITTED", discrepancyNo, "PENDING", blue.invoiceNo(), request.lineNo(), flushAmount);
        idempotencyService.complete(ENDPOINT_SUBMIT, request.requestId(), 200, response);
        return response;
    }

    /**
     * 销方处置差异单（幂等）。CONFIRM：双方确认差异，按原红字信息表流程创建部分红冲申请，
     * 差异单本身不改额度；REJECT：驳回并解除该行暂停，保留差异记录。
     * 满 72 小时（UTC）未答复已升级人工，处置返回 409。
     */
    public RespondDiscrepancyResponse respond(String discrepancyNo, RespondDiscrepancyRequest request) {
        validateRespond(request);
        var envelope = new RespondEnvelope(discrepancyNo, request);
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_RESPOND, envelope);
        var existing = idempotencyService.find(ENDPOINT_RESPOND, request.requestId());
        if (existing.isPresent()) {
            return deserializeRespond(
                    idempotencyService.replayOrReject(ENDPOINT_RESPOND, envelope, request.requestId()).body());
        }
        // 升级判断独立事务先落库，确保恰满 72 小时的处置看到 ESCALATED 状态
        escalateIfDue(discrepancyNo);
        try {
            return transactionTemplate.execute(status -> doRespond(discrepancyNo, request, envelope, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeRespond(
                    idempotencyService.replayOrReject(ENDPOINT_RESPOND, envelope, request.requestId()).body());
        }
    }

    private RespondDiscrepancyResponse doRespond(String discrepancyNo, RespondDiscrepancyRequest request,
                                                 RespondEnvelope envelope, String fingerprint) {
        idempotencyService.begin(ENDPOINT_RESPOND, request.requestId(), fingerprint);
        DiscrepancyRow row = lockByNo(discrepancyNo);
        if (isOverdue(row)) {
            throw ApiException.conflict("DISCREPANCY_ESCALATED",
                    "差异单已满 72 小时未答复，升级人工处理: " + discrepancyNo);
        }
        switch (row.status()) {
            case "ESCALATED" -> throw ApiException.conflict("DISCREPANCY_ESCALATED",
                    "差异单已升级人工处理: " + discrepancyNo);
            case "CONFIRMED", "REJECTED", "CONFLICT_REJECTED" ->
                    throw ApiException.conflict("DISCREPANCY_ALREADY_RESOLVED",
                            "差异单已处置（" + row.status() + "）: " + discrepancyNo);
            default -> {
                // PENDING：继续处置
            }
        }
        Instant now = clock.instant();
        if ("REJECT".equals(request.opinion())) {
            jdbc.update("UPDATE discrepancy_sheet SET status = 'REJECTED', responded_at = ?,"
                            + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    Timestamp.from(now), row.id());
            insertAudit(row.id(), "REJECT", "销方驳回差异，解除该行红冲暂停，差异记录保留", now);
            RespondDiscrepancyResponse response = new RespondDiscrepancyResponse(
                    "REJECTED", discrepancyNo, "REJECTED", null);
            idempotencyService.complete(ENDPOINT_RESPOND, request.requestId(), 200, response);
            return response;
        }
        // 双方确认差异：按原红字信息表流程处理需冲减金额；只有红票确认生效才恢复额度
        jdbc.update("UPDATE discrepancy_sheet SET status = 'CONFIRMED', responded_at = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                Timestamp.from(now), row.id());
        String redRequestNo = redFlushService.createLinkedRequest(
                row.blueInvoiceNo(), row.lineNo(), row.flushAmount());
        jdbc.update("UPDATE discrepancy_sheet SET red_request_no = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", redRequestNo, row.id());
        insertAudit(row.id(), "CONFIRM",
                "销方确认差异，按原流程创建红字信息表 " + redRequestNo + "，差异单本身不改额度", now);
        RespondDiscrepancyResponse response = new RespondDiscrepancyResponse(
                "CONFIRMED", discrepancyNo, "CONFIRMED", redRequestNo);
        idempotencyService.complete(ENDPOINT_RESPOND, request.requestId(), 200, response);
        return response;
    }

    /**
     * 查询差异单详情与审计轨迹。到期未答复在此同事务升级人工处理（恰满 72 小时即升级），
     * 不自动视为已确认；重复查询结果稳定。
     */
    public DiscrepancyView getDiscrepancy(String discrepancyNo) {
        return transactionTemplate.execute(status -> {
            DiscrepancyRow row = lockByNo(discrepancyNo);
            if (isOverdue(row)) {
                escalate(row, clock.instant());
                row = lockByNo(discrepancyNo);
            }
            return toView(row);
        });
    }

    /**
     * 若差异单已到期未答复则升级人工处理（独立事务落库）。
     */
    public void escalateIfDue(String discrepancyNo) {
        transactionTemplate.executeWithoutResult(status -> {
            DiscrepancyRow row;
            try {
                row = lockByNo(discrepancyNo);
            } catch (ApiException notFound) {
                return;
            }
            if (isOverdue(row)) {
                escalate(row, clock.instant());
            }
        });
    }

    private boolean isOverdue(DiscrepancyRow row) {
        return "PENDING".equals(row.status())
                && !clock.instant().isBefore(row.submittedAt().plus(ESCALATION_AFTER));
    }

    private void escalate(DiscrepancyRow row, Instant now) {
        jdbc.update("UPDATE discrepancy_sheet SET status = 'ESCALATED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", row.id());
        insertAudit(row.id(), "ESCALATE", "提交满 72 小时（UTC）无销方答复，升级人工处理，不自动视为已确认", now);
    }

    /**
     * 行可申请余额：行余额（行金额−行已红冲−在途行申请）与票余额（票金额−票已红冲−在途申请）
     * 孰小者。全额红冲只累计票面已红冲金额，票余额为零时整票各行均视为被红冲先占。
     */
    private BigDecimal effectiveLineAvailable(BlueRow blue, LineRow line) {
        BigDecimal pendingTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM red_flush_request"
                        + " WHERE blue_invoice_id = ? AND status = 'PENDING'",
                BigDecimal.class, blue.id());
        if (pendingTotal == null) {
            pendingTotal = BigDecimal.ZERO;
        }
        BigDecimal invoiceAvailable = blue.totalAmount().subtract(blue.redFlushedAmount())
                .subtract(pendingTotal);
        BigDecimal pendingLine = jdbc.queryForObject(
                "SELECT COALESCE(SUM(rl.amount), 0) FROM red_flush_line rl"
                        + " JOIN red_flush_request r ON rl.request_id = r.id"
                        + " WHERE r.blue_invoice_id = ? AND r.status = 'PENDING' AND rl.line_no = ?",
                BigDecimal.class, blue.id(), line.lineNo());
        if (pendingLine == null) {
            pendingLine = BigDecimal.ZERO;
        }
        BigDecimal lineAvailable = line.amount().subtract(line.redFlushedAmount()).subtract(pendingLine);
        return lineAvailable.min(invoiceAvailable);
    }

    private void validateSubmit(SubmitDiscrepancyRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.blueInvoiceNo() == null || request.blueInvoiceNo().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "blueInvoiceNo 不能为空");
        }
        if (request.lineNo() == null || request.lineNo() <= 0) {
            throw ApiException.badRequest("VALIDATION_FAILED", "lineNo 必须为正整数");
        }
        if (request.claimedAmount() == null || request.claimedAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("VALIDATION_FAILED", "购方主张金额不能为空或负数");
        }
        if (request.evidenceRef() == null || request.evidenceRef().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "合成凭据标识不能为空");
        }
    }

    private void validateRespond(RespondDiscrepancyRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.opinion() == null || !List.of("CONFIRM", "REJECT").contains(request.opinion())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "opinion 须为 CONFIRM 或 REJECT");
        }
    }

    private BlueRow lockBlueByNo(String invoiceNo) {
        return jdbc.query(
                        "SELECT id, invoice_no, status, total_amount, red_flushed_amount FROM invoice"
                                + " WHERE invoice_no = ? AND kind = 'BLUE' FOR UPDATE",
                        (rs, rowNum) -> new BlueRow(rs.getLong("id"), rs.getString("invoice_no"),
                                rs.getString("status"), rs.getBigDecimal("total_amount"),
                                rs.getBigDecimal("red_flushed_amount")), invoiceNo)
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

    private DiscrepancyRow lockByNo(String discrepancyNo) {
        return jdbc.query(
                        "SELECT id, discrepancy_no, blue_invoice_id, blue_invoice_no, blue_line_id, line_no,"
                                + " claimed_amount, flush_amount, evidence_ref, status, red_request_no,"
                                + " submitted_at, responded_at FROM discrepancy_sheet"
                                + " WHERE discrepancy_no = ? FOR UPDATE",
                        (rs, rowNum) -> mapRow(rs), discrepancyNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("DISCREPANCY_NOT_FOUND", "差异单不存在: " + discrepancyNo));
    }

    private DiscrepancyRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp respondedAt = rs.getTimestamp("responded_at");
        return new DiscrepancyRow(rs.getLong("id"), rs.getString("discrepancy_no"),
                rs.getLong("blue_invoice_id"), rs.getString("blue_invoice_no"), rs.getLong("blue_line_id"),
                rs.getInt("line_no"), rs.getBigDecimal("claimed_amount"), rs.getBigDecimal("flush_amount"),
                rs.getString("evidence_ref"), rs.getString("status"), rs.getString("red_request_no"),
                rs.getTimestamp("submitted_at").toInstant(),
                respondedAt == null ? null : respondedAt.toInstant());
    }

    private DiscrepancyView toView(DiscrepancyRow row) {
        List<AuditView> audits = jdbc.query(
                "SELECT action, detail, created_at FROM discrepancy_audit"
                        + " WHERE discrepancy_id = ? ORDER BY id",
                (rs, rowNum) -> new AuditView(rs.getString("action"), rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()),
                row.id());
        return new DiscrepancyView(row.discrepancyNo(), row.blueInvoiceNo(), row.lineNo(),
                row.claimedAmount(), row.flushAmount(), row.evidenceRef(), row.status(),
                row.redRequestNo(), row.submittedAt(), row.respondedAt(), audits);
    }

    private long insertDiscrepancy(BlueRow blue, LineRow line, SubmitDiscrepancyRequest request,
                                   BigDecimal flushAmount, String status, Instant now) {
        String tempNo = "TMP-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO discrepancy_sheet (discrepancy_no, blue_invoice_id, blue_invoice_no, blue_line_id,"
                        + " line_no, claimed_amount, flush_amount, evidence_ref, status, submitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tempNo, blue.id(), blue.invoiceNo(), line.id(), line.lineNo(), request.claimedAmount(),
                flushAmount, request.evidenceRef(), status, Timestamp.from(now));
        Long id = jdbc.queryForObject("SELECT id FROM discrepancy_sheet WHERE discrepancy_no = ?",
                Long.class, tempNo);
        String discrepancyNo = "DSP" + String.format("%08d", id);
        jdbc.update("UPDATE discrepancy_sheet SET discrepancy_no = ? WHERE id = ?", discrepancyNo, id);
        return id;
    }

    private String discrepancyNoOf(long id) {
        return jdbc.queryForObject("SELECT discrepancy_no FROM discrepancy_sheet WHERE id = ?",
                String.class, id);
    }

    private void insertAudit(long discrepancyId, String action, String detail, Instant now) {
        jdbc.update(
                "INSERT INTO discrepancy_audit (discrepancy_id, action, detail, created_at) VALUES (?, ?, ?, ?)",
                discrepancyId, action, detail, Timestamp.from(now));
    }

    private SubmitDiscrepancyResponse deserializeSubmit(String body) {
        try {
            return objectMapper.readValue(body, SubmitDiscrepancyResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private RespondDiscrepancyResponse deserializeRespond(String body) {
        try {
            return objectMapper.readValue(body, RespondDiscrepancyResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 蓝票内部行视图。
     */
    record BlueRow(long id, String invoiceNo, String status, BigDecimal totalAmount,
                   BigDecimal redFlushedAmount) {
    }

    /**
     * 蓝票行内部视图。
     */
    record LineRow(long id, int lineNo, BigDecimal amount, BigDecimal redFlushedAmount) {
    }

    /**
     * 差异单内部行视图。时间均为 UTC。
     */
    record DiscrepancyRow(long id, String discrepancyNo, long blueInvoiceId, String blueInvoiceNo,
                          long blueLineId, int lineNo, BigDecimal claimedAmount, BigDecimal flushAmount,
                          String evidenceRef, String status, String redRequestNo,
                          Instant submittedAt, Instant respondedAt) {
    }

    /**
     * 处置幂等指纹载体：覆盖路径中的差异单号与全部请求字段。
     */
    record RespondEnvelope(String discrepancyNo, RespondDiscrepancyRequest request) {
    }

    /**
     * 差异提交请求（幂等指纹覆盖全部字段）。
     */
    public record SubmitDiscrepancyRequest(String requestId, String blueInvoiceNo, Integer lineNo,
                                           BigDecimal claimedAmount, String evidenceRef) {
    }

    /**
     * 差异提交响应。result=SUBMITTED 已登记 / CONFLICT_REJECTED 红冲先占该行，拒绝并保留冲突记录。
     */
    public record SubmitDiscrepancyResponse(String result, String discrepancyNo, String status,
                                            String blueInvoiceNo, Integer lineNo, BigDecimal flushAmount) {
    }

    /**
     * 销方处置请求（幂等指纹覆盖全部字段）。opinion：CONFIRM 确认 / REJECT 驳回。
     */
    public record RespondDiscrepancyRequest(String requestId, String opinion) {
    }

    /**
     * 销方处置响应。redRequestNo 仅 CONFIRMED 时返回按原流程创建的红字信息表编号。
     */
    public record RespondDiscrepancyResponse(String result, String discrepancyNo, String status,
                                             String redRequestNo) {
    }

    /**
     * 审计轨迹视图。
     */
    public record AuditView(String action, String detail, Instant createdAt) {
    }

    /**
     * 差异单详情视图。时间均为 UTC。
     */
    public record DiscrepancyView(String discrepancyNo, String blueInvoiceNo, Integer lineNo,
                                  BigDecimal claimedAmount, BigDecimal flushAmount, String evidenceRef,
                                  String status, String redRequestNo, Instant submittedAt,
                                  Instant respondedAt, List<AuditView> audits) {
    }
}
