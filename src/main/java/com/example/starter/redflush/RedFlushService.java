package com.example.starter.redflush;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.common.ApiException;
import com.example.starter.credit.CreditService;
import com.example.starter.discrepancy.DiscrepancyGuard;
import com.example.starter.idempotency.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 红冲服务：红字信息表申请、购销双方确认与红票开具。
 * 规则：全额红冲须购方未入账或已取得购方确认；部分红冲按行项目拆分，
 * 累计红冲金额不得超过可红冲余额，恰红至余额为零合法；已全额红冲蓝票为终态，
 * 进一步红冲返回幂等结果不产生新红票；红字信息表须经购销双方在同一事务内
 * 先后确认方可开具红票，单方确认不生效，意见不一致挂起；红票确认后额度同事务恢复。
 * 行级争议隔离：蓝票行存在未结对账差异（OPEN/ESCALATED）时，只暂停该行的新增红冲，
 * 无争议行仍按原规则处理；购方未入账且没有差异的全额红冲沿用原路径，不新增挂起门槛。
 */
@Service
public class RedFlushService {

    static final String ENDPOINT_CREATE = "RED_FLUSH_CREATE";

    private final JdbcTemplate jdbc;
    private final CreditService creditService;
    private final IdempotencyService idempotencyService;
    private final DiscrepancyGuard discrepancyGuard;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public RedFlushService(JdbcTemplate jdbc, CreditService creditService,
                           IdempotencyService idempotencyService, DiscrepancyGuard discrepancyGuard,
                           TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.creditService = creditService;
        this.idempotencyService = idempotencyService;
        this.discrepancyGuard = discrepancyGuard;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 申请红字信息表（幂等）。已全额红冲蓝票返回幂等结果，不产生新红票。
     */
    public CreateRedFlushResponse createRequest(CreateRedFlushRequest request) {
        validateCreate(request);
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_CREATE, request);
        var existing = idempotencyService.find(ENDPOINT_CREATE, request.requestId());
        if (existing.isPresent()) {
            return deserializeCreate(
                    idempotencyService.replayOrReject(ENDPOINT_CREATE, request, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doCreateRequest(request, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeCreate(
                    idempotencyService.replayOrReject(ENDPOINT_CREATE, request, request.requestId()).body());
        }
    }

    private CreateRedFlushResponse doCreateRequest(CreateRedFlushRequest request, String fingerprint) {
        idempotencyService.begin(ENDPOINT_CREATE, request.requestId(), fingerprint);
        BlueInvoiceRow blue = lockBlueByNo(request.blueInvoiceNo());
        BigDecimal remaining = blue.totalAmount().subtract(blue.redFlushedAmount());
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            // 已全额红冲：终态幂等，不产生新红票
            CreateRedFlushResponse response = new CreateRedFlushResponse(
                    "ALREADY_RED_FLUSHED", null, null, blue.invoiceNo(), BigDecimal.ZERO);
            idempotencyService.complete(ENDPOINT_CREATE, request.requestId(), 200, response);
            return response;
        }
        if (!List.of("DELIVERED", "PARTIAL_RED", "ARCHIVED").contains(blue.status())) {
            throw ApiException.conflict("INVALID_STATE",
                    "蓝票状态 " + blue.status() + " 不允许红冲（须已交付或已归档）");
        }
        // 满 72 小时未答复的差异恰满即升级（同事务落库）；未结差异只暂停对应行的新增红冲
        discrepancyGuard.escalateExpired(blue.id());
        BigDecimal pendingTotal = pendingAmount(blue.id());
        BigDecimal remainingForNew = remaining.subtract(pendingTotal);
        BigDecimal amount;
        List<RedLineRequest> lines = List.of();
        if ("FULL".equals(request.flushType())) {
            if (discrepancyGuard.hasBlocking(blue.id())) {
                throw ApiException.conflict("LINE_DISPUTED",
                        "蓝票存在未结对账差异，全额红冲暂停，待差异处置后重试");
            }
            if (remainingForNew.compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.conflict("NO_REMAINING_BALANCE", "可红冲余额已被在途申请占满");
            }
            amount = remainingForNew;
        } else {
            Map<Integer, BigDecimal> lineBalances = lineBalances(blue.id());
            Map<Integer, BigDecimal> linePending = pendingLineAmounts(blue.id());
            amount = BigDecimal.ZERO;
            for (RedLineRequest line : request.lines()) {
                BigDecimal balance = lineBalances.get(line.lineNo());
                if (balance == null) {
                    throw ApiException.badRequest("LINE_NOT_FOUND", "蓝票不存在行号: " + line.lineNo());
                }
                if (discrepancyGuard.isLineBlocked(blue.id(), line.lineNo())) {
                    throw ApiException.conflict("LINE_DISPUTED",
                            "行 " + line.lineNo() + " 存在未结对账差异，该行新增红冲暂停，无争议行可单独申请");
                }
                BigDecimal allowed = balance.subtract(linePending.getOrDefault(line.lineNo(), BigDecimal.ZERO));
                if (line.amount().compareTo(allowed) > 0) {
                    throw ApiException.conflict("RED_FLUSH_EXCEEDS_LINE",
                            "行 " + line.lineNo() + " 累计红冲将超可红冲余额：可申请 " + allowed
                                    + "，本次 " + line.amount());
                }
                amount = amount.add(line.amount());
            }
            if (amount.compareTo(remainingForNew) > 0) {
                throw ApiException.conflict("RED_FLUSH_EXCEEDS_BALANCE",
                        "累计红冲将超可红冲余额：可申请 " + remainingForNew + "，本次 " + amount);
            }
            lines = request.lines();
        }
        long requestId = insertRequest(blue, request.flushType(), amount);
        String requestNo = jdbc.queryForObject(
                "SELECT request_no FROM red_flush_request WHERE id = ?", String.class, requestId);
        for (RedLineRequest line : lines) {
            Long blueLineId = jdbc.queryForObject(
                    "SELECT id FROM invoice_line WHERE invoice_id = ? AND line_no = ?",
                    Long.class, blue.id(), line.lineNo());
            jdbc.update(
                    "INSERT INTO red_flush_line (request_id, blue_line_id, line_no, amount) VALUES (?, ?, ?, ?)",
                    requestId, blueLineId, line.lineNo(), line.amount());
        }
        CreateRedFlushResponse response = new CreateRedFlushResponse(
                "CREATED", requestNo, "PENDING", blue.invoiceNo(), amount);
        idempotencyService.complete(ENDPOINT_CREATE, request.requestId(), 200, response);
        return response;
    }

    /**
     * 购销双方确认。单方确认不生效；双方在同一事务内先后确认后开具红票并同事务恢复额度；
     * 异议则挂起。重复确认幂等返回当前状态。
     */
    public ConfirmResponse confirm(String requestNo, ConfirmRequest request) {
        if (request.side() == null || !List.of("SELLER", "BUYER").contains(request.side())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "side 须为 SELLER 或 BUYER");
        }
        if (request.opinion() == null || !List.of("AGREE", "REJECT").contains(request.opinion())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "opinion 须为 AGREE 或 REJECT");
        }
        return transactionTemplate.execute(status -> doConfirm(requestNo, request));
    }

    private ConfirmResponse doConfirm(String requestNo, ConfirmRequest request) {
        RequestRow row = lockRequestByNo(requestNo);
        if (row.batchId() != null) {
            // 受控子单只能随整批原子确认，普通确认入口拒绝，防止绕过整批原子性
            throw ApiException.conflict("BATCH_CONTROLLED",
                    "受控子单须通过整批确认入口处理: " + requestNo);
        }
        if ("ISSUED".equals(row.status())) {
            return new ConfirmResponse(requestNo, "ISSUED", row.redInvoiceNo(),
                    creditService.computeAvailable(sellerOf(row.blueInvoiceId())));
        }
        if ("SUSPENDED".equals(row.status())) {
            throw ApiException.conflict("REQUEST_SUSPENDED", "红字信息表已挂起: " + requestNo);
        }
        jdbc.update(
                "INSERT INTO red_flush_confirmation (request_id, side, opinion) VALUES (?, ?, ?)",
                row.id(), request.side(), request.opinion());
        if ("REJECT".equals(request.opinion())) {
            jdbc.update("UPDATE red_flush_request SET status = 'SUSPENDED', updated_at = CURRENT_TIMESTAMP"
                    + " WHERE id = ?", row.id());
            return new ConfirmResponse(requestNo, "SUSPENDED", null,
                    creditService.computeAvailable(sellerOf(row.blueInvoiceId())));
        }
        boolean sellerConfirmed = row.sellerConfirmed() || "SELLER".equals(request.side());
        boolean buyerConfirmed = row.buyerConfirmed() || "BUYER".equals(request.side());
        jdbc.update("UPDATE red_flush_request SET seller_confirmed = ?, buyer_confirmed = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                sellerConfirmed, buyerConfirmed, row.id());
        if (!sellerConfirmed || !buyerConfirmed) {
            // 单方确认不生效
            return new ConfirmResponse(requestNo, "PENDING", null,
                    creditService.computeAvailable(sellerOf(row.blueInvoiceId())));
        }
        // 双方已确认：在同一事务内开具红票并恢复额度
        return issueRedInvoice(row, requestNo);
    }

    private ConfirmResponse issueRedInvoice(RequestRow row, String requestNo) {
        BlueInvoiceRow blue = lockBlueById(row.blueInvoiceId());
        BigDecimal remaining = blue.totalAmount().subtract(blue.redFlushedAmount());
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.conflict("NO_REMAINING_BALANCE", "可红冲余额不足，本次确认回滚且额度不变");
        }
        BigDecimal amount;
        List<RedLineRow> redLines = List.of();
        if ("FULL".equals(row.flushType())) {
            // 全额红冲：购方已入账时须已取得购方确认（双方确认已满足，此处显式校验）
            if (blue.buyerBooked() && !hasBuyerAgree(row.id())) {
                throw ApiException.conflict("BUYER_CONFIRM_REQUIRED", "购方已入账，全额红冲须取得购方确认");
            }
            amount = remaining;
        } else {
            redLines = jdbc.query(
                    "SELECT blue_line_id, line_no, amount FROM red_flush_line WHERE request_id = ?",
                    (rs, rowNum) -> new RedLineRow(rs.getLong("blue_line_id"), rs.getInt("line_no"),
                            rs.getBigDecimal("amount")),
                    row.id());
            amount = redLines.stream().map(RedLineRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<Integer, BigDecimal> lineBalances = lineBalances(blue.id());
            for (RedLineRow line : redLines) {
                if (line.amount().compareTo(lineBalances.get(line.lineNo())) > 0) {
                    throw ApiException.conflict("RED_FLUSH_EXCEEDS_LINE",
                            "行 " + line.lineNo() + " 累计红冲超可红冲余额，本次确认回滚且额度不变");
                }
            }
            if (amount.compareTo(remaining) > 0) {
                throw ApiException.conflict("RED_FLUSH_EXCEEDS_BALANCE",
                        "累计红冲超可红冲余额，本次确认回滚且额度不变");
            }
        }
        // 更新蓝票行项目与累计红冲金额
        for (RedLineRow line : redLines) {
            jdbc.update("UPDATE invoice_line SET red_flushed_amount = red_flushed_amount + ?"
                    + " WHERE id = ?", line.amount(), line.blueLineId());
        }
        BigDecimal newFlushed = blue.redFlushedAmount().add(amount);
        boolean fullyFlushed = newFlushed.compareTo(blue.totalAmount()) == 0;
        String newStatus;
        if ("ARCHIVED".equals(blue.status())) {
            // 归档票红冲不改变归档状态，仅累计红冲金额
            newStatus = "ARCHIVED";
        } else {
            newStatus = fullyFlushed ? "RED_FLUSHED" : "PARTIAL_RED";
        }
        jdbc.update("UPDATE invoice SET red_flushed_amount = ?, status = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", newFlushed, newStatus, blue.id());
        // 开具红票，引用原蓝票号码，保留完整红冲链
        long redInvoiceId = insertRedInvoice(blue, amount);
        String redInvoiceNo = jdbc.queryForObject(
                "SELECT invoice_no FROM invoice WHERE id = ?", String.class, redInvoiceId);
        // 额度在同一事务内恢复
        creditService.lockAccount(blue.sellerId());
        BigDecimal available = creditService.computeAvailable(blue.sellerId());
        creditService.appendLedger(blue.sellerId(), amount, "RED_CONFIRM", requestNo, available);
        jdbc.update("UPDATE red_flush_request SET status = 'ISSUED', red_invoice_no = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", redInvoiceNo, row.id());
        return new ConfirmResponse(requestNo, "ISSUED", redInvoiceNo, available);
    }

    /**
     * 整批生效时在批事务内为受控子单开具红票并同事务恢复额度（不开新事务、不校验双方确认，
     * 整批原子性由批服务保证）。仅允许 PENDING 状态的受控子单；批状态机保证不会重复生效。
     *
     * @return 开具的红票号码
     */
    public String issueControlledRequest(String requestNo) {
        RequestRow row = lockRequestByNo(requestNo);
        if (row.batchId() == null) {
            throw ApiException.conflict("NOT_BATCH_CONTROLLED", "非整批受控子单: " + requestNo);
        }
        if (!"PENDING".equals(row.status())) {
            throw ApiException.conflict("INVALID_STATE",
                    "受控子单状态 " + row.status() + " 不允许开具红票");
        }
        return issueRedInvoice(row, requestNo).redInvoiceNo();
    }

    /**
     * 查询红字信息表：状态、行项目与双方确认轨迹，重复查询结果稳定。
     */
    public RedFlushRequestView getRequest(String requestNo) {
        RequestRow row = findRequestByNo(requestNo);
        List<RedFlushLineView> lines = jdbc.query(
                "SELECT line_no, amount FROM red_flush_line WHERE request_id = ? ORDER BY line_no",
                (rs, rowNum) -> new RedFlushLineView(rs.getInt("line_no"), rs.getBigDecimal("amount")),
                row.id());
        List<ConfirmationView> confirmations = jdbc.query(
                "SELECT side, opinion, created_at FROM red_flush_confirmation"
                        + " WHERE request_id = ? ORDER BY id",
                (rs, rowNum) -> new ConfirmationView(rs.getString("side"), rs.getString("opinion"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                row.id());
        return new RedFlushRequestView(row.requestNo(), row.blueInvoiceNo(), row.flushType(),
                row.totalAmount(), row.status(), row.sellerConfirmed(), row.buyerConfirmed(),
                row.redInvoiceNo(), lines, confirmations);
    }

    private void validateCreate(CreateRedFlushRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.blueInvoiceNo() == null || request.blueInvoiceNo().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "blueInvoiceNo 不能为空");
        }
        if (request.flushType() == null || !List.of("FULL", "PARTIAL").contains(request.flushType())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "flushType 须为 FULL 或 PARTIAL");
        }
        if ("PARTIAL".equals(request.flushType())) {
            if (request.lines() == null || request.lines().isEmpty()) {
                throw ApiException.badRequest("VALIDATION_FAILED", "部分红冲必须按行项目拆分");
            }
            for (RedLineRequest line : request.lines()) {
                if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw ApiException.badRequest("VALIDATION_FAILED", "行红冲金额必须为正数");
                }
            }
        }
    }

    BlueInvoiceRow lockBlueByNo(String invoiceNo) {
        return jdbc.query(
                        "SELECT id, invoice_no, seller_id, buyer_id, buyer_booked, total_amount,"
                                + " red_flushed_amount, status FROM invoice"
                                + " WHERE invoice_no = ? AND kind = 'BLUE' FOR UPDATE",
                        (rs, rowNum) -> mapBlue(rs), invoiceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "蓝票不存在: " + invoiceNo));
    }

    private BlueInvoiceRow lockBlueById(long id) {
        return jdbc.query(
                        "SELECT id, invoice_no, seller_id, buyer_id, buyer_booked, total_amount,"
                                + " red_flushed_amount, status FROM invoice"
                                + " WHERE id = ? AND kind = 'BLUE' FOR UPDATE",
                        (rs, rowNum) -> mapBlue(rs), id)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "蓝票不存在: " + id));
    }

    private BlueInvoiceRow mapBlue(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BlueInvoiceRow(rs.getLong("id"), rs.getString("invoice_no"), rs.getString("seller_id"),
                rs.getString("buyer_id"), rs.getBoolean("buyer_booked"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("red_flushed_amount"), rs.getString("status"));
    }

    private RequestRow lockRequestByNo(String requestNo) {
        return jdbc.query(
                        "SELECT id, request_no, blue_invoice_id, blue_invoice_no, flush_type, total_amount,"
                                + " status, seller_confirmed, buyer_confirmed, red_invoice_no, batch_id"
                                + " FROM red_flush_request WHERE request_no = ? FOR UPDATE",
                        (rs, rowNum) -> mapRequest(rs), requestNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "红字信息表不存在: " + requestNo));
    }

    private RequestRow findRequestByNo(String requestNo) {
        return jdbc.query(
                        "SELECT id, request_no, blue_invoice_id, blue_invoice_no, flush_type, total_amount,"
                                + " status, seller_confirmed, buyer_confirmed, red_invoice_no, batch_id"
                                + " FROM red_flush_request WHERE request_no = ?",
                        (rs, rowNum) -> mapRequest(rs), requestNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "红字信息表不存在: " + requestNo));
    }

    private RequestRow mapRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        long batchId = rs.getLong("batch_id");
        Long batchIdOrNull = rs.wasNull() ? null : batchId;
        return new RequestRow(rs.getLong("id"), rs.getString("request_no"), rs.getLong("blue_invoice_id"),
                rs.getString("blue_invoice_no"), rs.getString("flush_type"), rs.getBigDecimal("total_amount"),
                rs.getString("status"), rs.getBoolean("seller_confirmed"), rs.getBoolean("buyer_confirmed"),
                rs.getString("red_invoice_no"), batchIdOrNull);
    }

    /**
     * 各行剩余可红冲余额（行金额−行累计已红冲）。
     */
    Map<Integer, BigDecimal> lineBalances(long blueInvoiceId) {
        Map<Integer, BigDecimal> balances = new HashMap<>();
        jdbc.query("SELECT line_no, amount, red_flushed_amount FROM invoice_line WHERE invoice_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> balances.put(rs.getInt("line_no"),
                        rs.getBigDecimal("amount").subtract(rs.getBigDecimal("red_flushed_amount"))),
                blueInvoiceId);
        return balances;
    }

    /**
     * 在途（待确认）申请合计金额。
     */
    BigDecimal pendingAmount(long blueInvoiceId) {
        BigDecimal pending = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM red_flush_request"
                        + " WHERE blue_invoice_id = ? AND status = 'PENDING'",
                BigDecimal.class, blueInvoiceId);
        return pending == null ? BigDecimal.ZERO : pending;
    }

    /**
     * 在途申请按行合计金额。
     */
    Map<Integer, BigDecimal> pendingLineAmounts(long blueInvoiceId) {
        Map<Integer, BigDecimal> pending = new HashMap<>();
        jdbc.query(
                "SELECT rl.line_no, COALESCE(SUM(rl.amount), 0) AS pending FROM red_flush_line rl"
                        + " JOIN red_flush_request r ON rl.request_id = r.id"
                        + " WHERE r.blue_invoice_id = ? AND r.status = 'PENDING' GROUP BY rl.line_no",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        pending.put(rs.getInt("line_no"), rs.getBigDecimal("pending")),
                blueInvoiceId);
        return pending;
    }
    private boolean hasBuyerAgree(long requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_confirmation"
                        + " WHERE request_id = ? AND side = 'BUYER' AND opinion = 'AGREE'",
                Integer.class, requestId);
        return count != null && count > 0;
    }

    private String sellerOf(long blueInvoiceId) {
        return jdbc.queryForObject("SELECT seller_id FROM invoice WHERE id = ?", String.class, blueInvoiceId);
    }

    private long insertRequest(BlueInvoiceRow blue, String flushType, BigDecimal amount) {
        return insertRequest(blue, flushType, amount, null);
    }

    /**
     * 插入红字信息表并回写正式编号；batchId 非空时登记为整批受控子单。
     */
    long insertRequest(BlueInvoiceRow blue, String flushType, BigDecimal amount, Long batchId) {
        String tempNo = "TMP-" + java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO red_flush_request (request_no, blue_invoice_id, blue_invoice_no, flush_type,"
                        + " total_amount, status, batch_id) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
                tempNo, blue.id(), blue.invoiceNo(), flushType, amount, batchId);
        Long id = jdbc.queryForObject("SELECT id FROM red_flush_request WHERE request_no = ?",
                Long.class, tempNo);
        String requestNo = "RFR" + String.format("%08d", id);
        jdbc.update("UPDATE red_flush_request SET request_no = ? WHERE id = ?", requestNo, id);
        return id;
    }

    private long insertRedInvoice(BlueInvoiceRow blue, BigDecimal amount) {
        String tempNo = "TMP-" + java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO invoice (invoice_no, kind, seller_id, buyer_id, buyer_booked, total_amount,"
                        + " status, blue_invoice_no) VALUES (?, 'RED', ?, ?, FALSE, ?, 'ISSUED', ?)",
                tempNo, blue.sellerId(), blue.buyerId(), amount, blue.invoiceNo());
        Long id = jdbc.queryForObject("SELECT id FROM invoice WHERE invoice_no = ?", Long.class, tempNo);
        String invoiceNo = "RED" + String.format("%08d", id);
        jdbc.update("UPDATE invoice SET invoice_no = ? WHERE id = ?", invoiceNo, id);
        return id;
    }

    private CreateRedFlushResponse deserializeCreate(String body) {
        try {
            return objectMapper.readValue(body, CreateRedFlushResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 差异单确认后按原红字信息表流程创建部分红冲申请（系统内部调用，无独立幂等键，
     * 幂等性由差异处置的 requestId 保证）。须在调用方事务内执行；
     * 只创建 PENDING 申请，不开红票、不动额度，额度仍只在红票确认生效后恢复。
     *
     * @return 新建红字信息表编号
     */
    public String createSystemRequest(String blueInvoiceNo, int lineNo, BigDecimal amount) {
        BlueInvoiceRow blue = lockBlueByNo(blueInvoiceNo);
        Map<Integer, BigDecimal> balances = lineBalances(blue.id());
        BigDecimal balance = balances.get(lineNo);
        if (balance == null) {
            throw ApiException.badRequest("LINE_NOT_FOUND", "蓝票不存在行号: " + lineNo);
        }
        if (amount.compareTo(balance) > 0) {
            throw ApiException.conflict("RED_FLUSH_EXCEEDS_LINE",
                    "行 " + lineNo + " 差异冲减金额超可红冲余额：可冲 " + balance + "，本次 " + amount);
        }
        long requestId = insertRequest(blue, "PARTIAL", amount);
        String requestNo = jdbc.queryForObject(
                "SELECT request_no FROM red_flush_request WHERE id = ?", String.class, requestId);
        Long blueLineId = jdbc.queryForObject(
                "SELECT id FROM invoice_line WHERE invoice_id = ? AND line_no = ?",
                Long.class, blue.id(), lineNo);
        jdbc.update(
                "INSERT INTO red_flush_line (request_id, blue_line_id, line_no, amount) VALUES (?, ?, ?, ?)",
                requestId, blueLineId, lineNo, amount);
        return requestNo;
    }

    /**
     * 蓝票内部行视图。
     */
    record BlueInvoiceRow(long id, String invoiceNo, String sellerId, String buyerId, boolean buyerBooked,
                          BigDecimal totalAmount, BigDecimal redFlushedAmount, String status) {
    }

    /**
     * 红字信息表内部行视图。batchId 非空时为整批受控子单。
     */
    record RequestRow(long id, String requestNo, long blueInvoiceId, String blueInvoiceNo, String flushType,
                      BigDecimal totalAmount, String status, boolean sellerConfirmed, boolean buyerConfirmed,
                      String redInvoiceNo, Long batchId) {
    }

    /**
     * 红冲行内部视图。
     */
    record RedLineRow(long blueLineId, int lineNo, BigDecimal amount) {
    }

    /**
     * 红冲申请行项目。
     */
    public record RedLineRequest(Integer lineNo, BigDecimal amount) {
    }

    /**
     * 红冲申请请求（幂等指纹覆盖全部字段）。
     */
    public record CreateRedFlushRequest(String requestId, String blueInvoiceNo, String flushType,
                                        List<RedLineRequest> lines) {
    }

    /**
     * 红冲申请响应。result=ALREADY_RED_FLUSHED 时为终态幂等结果，不产生新红票。
     */
    public record CreateRedFlushResponse(String result, String requestNo, String status,
                                         String blueInvoiceNo, BigDecimal totalAmount) {
    }

    /**
     * 确认请求。
     */
    public record ConfirmRequest(String side, String opinion) {
    }

    /**
     * 确认响应。
     */
    public record ConfirmResponse(String requestNo, String status, String redInvoiceNo,
                                  BigDecimal availableLimit) {
    }

    /**
     * 红冲行视图。
     */
    public record RedFlushLineView(int lineNo, BigDecimal amount) {
    }

    /**
     * 确认轨迹视图。
     */
    public record ConfirmationView(String side, String opinion, LocalDateTime createdAt) {
    }

    /**
     * 红字信息表详情视图。
     */
    public record RedFlushRequestView(String requestNo, String blueInvoiceNo, String flushType,
                                      BigDecimal totalAmount, String status, boolean sellerConfirmed,
                                      boolean buyerConfirmed, String redInvoiceNo,
                                      List<RedFlushLineView> lines, List<ConfirmationView> confirmations) {
    }
}
