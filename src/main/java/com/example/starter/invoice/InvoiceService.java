package com.example.starter.invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.example.starter.common.ApiException;
import com.example.starter.credit.CreditService;
import com.example.starter.idempotency.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 蓝字发票服务：开具→交付→（可红冲）→归档。
 * 开票按 requestId 幂等；可用额度恰等于开票金额合法，超出拒绝且额度不变；
 * 并发开票通过授信账户行锁串行校验，不得合计超额。
 * 归档为终态：重复归档幂等成功；归档后只许红冲，禁止修改原始票面。
 */
@Service
public class InvoiceService {

    static final String ENDPOINT_ISSUE = "ISSUE_BLUE";

    private final JdbcTemplate jdbc;
    private final CreditService creditService;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public InvoiceService(JdbcTemplate jdbc, CreditService creditService,
                          IdempotencyService idempotencyService,
                          TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.creditService = creditService;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 开具蓝票（幂等）。同键同参返回首次结果，同键异参返回 409 且额度不变。
     */
    public IssueResponse issueBlue(IssueBlueInvoiceRequest request) {
        validateIssue(request);
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_ISSUE, request);
        var existing = idempotencyService.find(ENDPOINT_ISSUE, request.requestId());
        if (existing.isPresent()) {
            return deserialize(idempotencyService.replayOrReject(ENDPOINT_ISSUE, request, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doIssueBlue(request, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发同键：首次请求已提交，重放其结果
            return deserialize(idempotencyService.replayOrReject(ENDPOINT_ISSUE, request, request.requestId()).body());
        }
    }

    private IssueResponse doIssueBlue(IssueBlueInvoiceRequest request, String fingerprint) {
        idempotencyService.begin(ENDPOINT_ISSUE, request.requestId(), fingerprint);
        BigDecimal totalAmount = request.lines().stream()
                .map(LineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 串行校验：锁定授信账户后再复算可用额度
        creditService.lockAccount(request.sellerId());
        BigDecimal available = creditService.computeAvailable(request.sellerId());
        if (totalAmount.compareTo(available) > 0) {
            throw ApiException.conflict("INSUFFICIENT_CREDIT",
                    "可用额度不足：可用 " + available + "，申请 " + totalAmount + "，额度不变");
        }
        long invoiceId = insertInvoice("INV", "BLUE", request.sellerId(), request.buyerId(),
                request.buyerBooked(), totalAmount, "ISSUED", null);
        String invoiceNo = jdbc.queryForObject(
                "SELECT invoice_no FROM invoice WHERE id = ?", String.class, invoiceId);
        int lineNo = 1;
        for (LineRequest line : request.lines()) {
            jdbc.update(
                    "INSERT INTO invoice_line (invoice_id, line_no, item_name, amount) VALUES (?, ?, ?, ?)",
                    invoiceId, lineNo++, line.itemName(), line.amount());
        }
        creditService.appendLedger(request.sellerId(), totalAmount.negate(), "ISSUE", invoiceNo,
                available.subtract(totalAmount));
        IssueResponse response = new IssueResponse(invoiceNo, "ISSUED", totalAmount,
                available.subtract(totalAmount));
        idempotencyService.complete(ENDPOINT_ISSUE, request.requestId(), 200, response);
        return response;
    }

    /**
     * 交付蓝票：ISSUED→DELIVERED；重复交付幂等成功；终态（已红冲/已归档）返回 409。
     */
    public ActionResponse deliver(String invoiceNo) {
        return transactionTemplate.execute(status -> {
            InvoiceRow invoice = lockByNo(invoiceNo);
            if (!"BLUE".equals(invoice.kind())) {
                throw ApiException.badRequest("NOT_BLUE_INVOICE", "非蓝字发票: " + invoiceNo);
            }
            switch (invoice.status()) {
                case "DELIVERED" -> {
                    return new ActionResponse(invoiceNo, "DELIVERED",
                            creditService.computeAvailable(invoice.sellerId()));
                }
                case "ISSUED" -> {
                    jdbc.update("UPDATE invoice SET status = 'DELIVERED', updated_at = CURRENT_TIMESTAMP"
                            + " WHERE id = ?", invoice.id());
                    return new ActionResponse(invoiceNo, "DELIVERED",
                            creditService.computeAvailable(invoice.sellerId()));
                }
                default -> throw ApiException.conflict("INVALID_STATE",
                        "当前状态 " + invoice.status() + " 不允许交付");
            }
        });
    }

    /**
     * 归档蓝票：DELIVERED/PARTIAL_RED→ARCHIVED 并释放占用额度；重复归档幂等成功；
     * 未交付或已全额红冲（终态）返回 409。
     */
    public ActionResponse archive(String invoiceNo) {
        return transactionTemplate.execute(status -> {
            InvoiceRow invoice = lockByNo(invoiceNo);
            if (!"BLUE".equals(invoice.kind())) {
                throw ApiException.badRequest("NOT_BLUE_INVOICE", "非蓝字发票: " + invoiceNo);
            }
            switch (invoice.status()) {
                case "ARCHIVED" -> {
                    // 重复归档：幂等成功，不重复释放额度
                    return new ActionResponse(invoiceNo, "ARCHIVED",
                            creditService.computeAvailable(invoice.sellerId()));
                }
                case "DELIVERED", "PARTIAL_RED" -> {
                    creditService.lockAccount(invoice.sellerId());
                    BigDecimal available = creditService.computeAvailable(invoice.sellerId());
                    jdbc.update("UPDATE invoice SET status = 'ARCHIVED', updated_at = CURRENT_TIMESTAMP"
                            + " WHERE id = ?", invoice.id());
                    creditService.appendLedger(invoice.sellerId(), invoice.totalAmount(), "ARCHIVE",
                            invoiceNo, available.add(invoice.totalAmount()));
                    return new ActionResponse(invoiceNo, "ARCHIVED",
                            available.add(invoice.totalAmount()));
                }
                default -> throw ApiException.conflict("INVALID_STATE",
                        "当前状态 " + invoice.status() + " 不允许归档");
            }
        });
    }

    /**
     * 查询发票详情：票面、行项目及红冲链（引用本票的红票），重复查询结果稳定。
     */
    public InvoiceView getInvoice(String invoiceNo) {
        InvoiceRow invoice = findByNo(invoiceNo);
        List<LineView> lines = jdbc.query(
                "SELECT line_no, item_name, amount, red_flushed_amount FROM invoice_line"
                        + " WHERE invoice_id = ? ORDER BY line_no",
                (rs, rowNum) -> new LineView(rs.getInt("line_no"), rs.getString("item_name"),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("red_flushed_amount")),
                invoice.id());
        List<RedInvoiceRef> redChain = jdbc.query(
                "SELECT invoice_no, total_amount, created_at FROM invoice"
                        + " WHERE kind = 'RED' AND blue_invoice_no = ? ORDER BY id",
                (rs, rowNum) -> new RedInvoiceRef(rs.getString("invoice_no"),
                        rs.getBigDecimal("total_amount"), rs.getTimestamp("created_at").toLocalDateTime()),
                invoiceNo);
        return new InvoiceView(invoice.invoiceNo(), invoice.kind(), invoice.sellerId(), invoice.buyerId(),
                invoice.buyerBooked(), invoice.totalAmount(), invoice.redFlushedAmount(), invoice.status(),
                invoice.blueInvoiceNo(), lines, redChain);
    }

    private void validateIssue(IssueBlueInvoiceRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.sellerId() == null || request.sellerId().isBlank()
                || request.buyerId() == null || request.buyerId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "sellerId/buyerId 不能为空");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "行项目不能为空");
        }
        for (LineRequest line : request.lines()) {
            if (line.itemName() == null || line.itemName().isBlank()
                    || line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("VALIDATION_FAILED", "行项目名称不能为空且金额必须为正数");
            }
        }
    }

    /**
     * 插入发票主行并回写正式发票号码（前缀+主键序号）。
     */
    long insertInvoice(String noPrefix, String kind, String sellerId, String buyerId, boolean buyerBooked,
                       BigDecimal totalAmount, String status, String blueInvoiceNo) {
        String tempNo = "TMP-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invoice (invoice_no, kind, seller_id, buyer_id, buyer_booked, total_amount,"
                        + " status, blue_invoice_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tempNo, kind, sellerId, buyerId, buyerBooked, totalAmount, status, blueInvoiceNo);
        Long id = jdbc.queryForObject("SELECT id FROM invoice WHERE invoice_no = ?", Long.class, tempNo);
        String invoiceNo = noPrefix + String.format("%08d", id);
        jdbc.update("UPDATE invoice SET invoice_no = ? WHERE id = ?", invoiceNo, id);
        return id;
    }

    /**
     * 按发票号加行级悲观锁读取（须在事务内调用）。
     */
    InvoiceRow lockByNo(String invoiceNo) {
        return jdbc.query(
                        "SELECT id, invoice_no, kind, seller_id, buyer_id, buyer_booked, total_amount,"
                                + " red_flushed_amount, status, blue_invoice_no FROM invoice"
                                + " WHERE invoice_no = ? FOR UPDATE",
                        (rs, rowNum) -> mapRow(rs), invoiceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "发票不存在: " + invoiceNo));
    }

    InvoiceRow findByNo(String invoiceNo) {
        return jdbc.query(
                        "SELECT id, invoice_no, kind, seller_id, buyer_id, buyer_booked, total_amount,"
                                + " red_flushed_amount, status, blue_invoice_no FROM invoice WHERE invoice_no = ?",
                        (rs, rowNum) -> mapRow(rs), invoiceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "发票不存在: " + invoiceNo));
    }

    private InvoiceRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InvoiceRow(rs.getLong("id"), rs.getString("invoice_no"), rs.getString("kind"),
                rs.getString("seller_id"), rs.getString("buyer_id"), rs.getBoolean("buyer_booked"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("red_flushed_amount"),
                rs.getString("status"), rs.getString("blue_invoice_no"));
    }

    private IssueResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, IssueResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 发票主行内部视图。
     */
    record InvoiceRow(long id, String invoiceNo, String kind, String sellerId, String buyerId,
                      boolean buyerBooked, BigDecimal totalAmount, BigDecimal redFlushedAmount,
                      String status, String blueInvoiceNo) {
    }

    /**
     * 开票请求行项目。
     */
    public record LineRequest(String itemName, BigDecimal amount) {
    }

    /**
     * 开票请求（幂等指纹覆盖全部字段）。
     */
    public record IssueBlueInvoiceRequest(String requestId, String sellerId, String buyerId,
                                          boolean buyerBooked, List<LineRequest> lines) {
    }

    /**
     * 开票响应。
     */
    public record IssueResponse(String invoiceNo, String status, BigDecimal totalAmount,
                                BigDecimal availableLimit) {
    }

    /**
     * 状态流转响应。
     */
    public record ActionResponse(String invoiceNo, String status, BigDecimal availableLimit) {
    }

    /**
     * 行项目视图。
     */
    public record LineView(int lineNo, String itemName, BigDecimal amount, BigDecimal redFlushedAmount) {
    }

    /**
     * 红冲链中的红票引用。
     */
    public record RedInvoiceRef(String invoiceNo, BigDecimal amount, java.time.LocalDateTime createdAt) {
    }

    /**
     * 发票详情视图。
     */
    public record InvoiceView(String invoiceNo, String kind, String sellerId, String buyerId,
                              boolean buyerBooked, BigDecimal totalAmount, BigDecimal redFlushedAmount,
                              String status, String blueInvoiceNo, List<LineView> lines,
                              List<RedInvoiceRef> redChain) {
    }
}
