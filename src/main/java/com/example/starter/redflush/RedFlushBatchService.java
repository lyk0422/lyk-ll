package com.example.starter.redflush;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.common.ApiException;
import com.example.starter.credit.CreditService;
import com.example.starter.discrepancy.DiscrepancyGuard;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.redflush.RedFlushService.BlueInvoiceRow;
import com.example.starter.redflush.RedFlushService.RedFlushLineView;
import com.example.starter.redflush.RedFlushService.RedLineRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 整批红冲服务：同一销购双方 2~10 张蓝票一批受理，批内每票生成受控子单。
 * 受理时逐行检查票据可红冲、余额及在途争议并预占全部行的可红冲余额（子单 PENDING 即预占，
 * 与普通红冲、差异登记共享行互斥）；任一行不符整批 422，不留下子单或占用；预占不恢复授信。
 * 购销双方分别确认整批，单方同意只留意见；双方均同意才在一个事务生成全部红票、
 * 消费预占并恢复对应额度；任一方拒绝则整批终止、释放全部占用；未生效批可撤回，生效批不可撤回。
 * 批版本从 1 起，修改带 expectedVersion，成功加 1、过期 409；写入按 requestId 幂等，
 * 业务、版本与幂等同事务。金额为合成规则，批时间统一 UTC，不连接税务平台。
 */
@Service
public class RedFlushBatchService {

    static final String ENDPOINT_BATCH_CREATE = "RED_FLUSH_BATCH_CREATE";
    static final String ENDPOINT_BATCH_CONFIRM = "RED_FLUSH_BATCH_CONFIRM";
    static final String ENDPOINT_BATCH_WITHDRAW = "RED_FLUSH_BATCH_WITHDRAW";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RedFlushService redFlushService;
    private final CreditService creditService;
    private final DiscrepancyGuard discrepancyGuard;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public RedFlushBatchService(JdbcTemplate jdbc, Clock clock, RedFlushService redFlushService,
                                CreditService creditService, DiscrepancyGuard discrepancyGuard,
                                IdempotencyService idempotencyService,
                                TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.redFlushService = redFlushService;
        this.creditService = creditService;
        this.discrepancyGuard = discrepancyGuard;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 受理整批红冲（幂等）。任一行不符整批 422 且事务回滚，不留下子单或占用。
     */
    public CreateBatchResponse createBatch(CreateBatchRequest request) {
        validateCreate(request);
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_BATCH_CREATE, request);
        var existing = idempotencyService.find(ENDPOINT_BATCH_CREATE, request.requestId());
        if (existing.isPresent()) {
            return deserializeCreate(idempotencyService
                    .replayOrReject(ENDPOINT_BATCH_CREATE, request, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doCreateBatch(request, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发同键：首次请求已提交，重放其结果
            return deserializeCreate(idempotencyService
                    .replayOrReject(ENDPOINT_BATCH_CREATE, request, request.requestId()).body());
        }
    }

    private CreateBatchResponse doCreateBatch(CreateBatchRequest request, String fingerprint) {
        idempotencyService.begin(ENDPOINT_BATCH_CREATE, request.requestId(), fingerprint);
        // 锁序：按票号升序逐票加行锁，避免并发批之间死锁
        List<BatchItemRequest> items = request.items().stream()
                .sorted(Comparator.comparing(BatchItemRequest::blueInvoiceNo))
                .toList();
        List<BlueInvoiceRow> blues = new ArrayList<>();
        for (BatchItemRequest item : items) {
            blues.add(lockBlue(item.blueInvoiceNo()));
        }
        String sellerId = blues.get(0).sellerId();
        String buyerId = blues.get(0).buyerId();
        for (BlueInvoiceRow blue : blues) {
            if (!sellerId.equals(blue.sellerId()) || !buyerId.equals(blue.buyerId())) {
                throw ApiException.unprocessable("BATCH_PARTY_MISMATCH",
                        "批内各票须为同一销购双方: " + blue.invoiceNo());
            }
        }
        // 逐行检查可红冲、余额及在途争议；任一不符整批 422 回滚
        List<BigDecimal> itemAmounts = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            BlueInvoiceRow blue = blues.get(i);
            if (!List.of("DELIVERED", "PARTIAL_RED", "ARCHIVED").contains(blue.status())) {
                throw ApiException.unprocessable("INVALID_STATE",
                        "蓝票 " + blue.invoiceNo() + " 状态 " + blue.status() + " 不允许红冲（须已交付或已归档）");
            }
            // 满 72 小时未答复的差异恰满即升级（同事务落库）；未结差异只暂停对应行
            discrepancyGuard.escalateExpired(blue.id());
            BigDecimal remaining = blue.totalAmount().subtract(blue.redFlushedAmount());
            BigDecimal remainingForNew = remaining.subtract(redFlushService.pendingAmount(blue.id()));
            Map<Integer, BigDecimal> lineBalances = redFlushService.lineBalances(blue.id());
            Map<Integer, BigDecimal> linePending = redFlushService.pendingLineAmounts(blue.id());
            BigDecimal itemAmount = BigDecimal.ZERO;
            for (RedLineRequest line : items.get(i).lines()) {
                BigDecimal balance = lineBalances.get(line.lineNo());
                if (balance == null) {
                    throw ApiException.unprocessable("LINE_NOT_FOUND",
                            "蓝票 " + blue.invoiceNo() + " 不存在行号: " + line.lineNo());
                }
                if (discrepancyGuard.isLineBlocked(blue.id(), line.lineNo())) {
                    throw ApiException.unprocessable("LINE_DISPUTED",
                            "蓝票 " + blue.invoiceNo() + " 行 " + line.lineNo()
                                    + " 存在未结对账差异，该行新增红冲暂停");
                }
                BigDecimal allowed = balance.subtract(linePending.getOrDefault(line.lineNo(), BigDecimal.ZERO));
                if (line.amount().compareTo(allowed) > 0) {
                    throw ApiException.unprocessable("RED_FLUSH_EXCEEDS_LINE",
                            "蓝票 " + blue.invoiceNo() + " 行 " + line.lineNo()
                                    + " 累计红冲将超可红冲余额：可申请 " + allowed + "，本次 " + line.amount());
                }
                itemAmount = itemAmount.add(line.amount());
            }
            if (itemAmount.compareTo(remainingForNew) > 0) {
                throw ApiException.unprocessable("RED_FLUSH_EXCEEDS_BALANCE",
                        "蓝票 " + blue.invoiceNo() + " 累计红冲将超可红冲余额：可申请 "
                                + remainingForNew + "，本次 " + itemAmount);
            }
            itemAmounts.add(itemAmount);
            totalAmount = totalAmount.add(itemAmount);
        }
        // 全部检查通过后一次性落库：批 + 各票受控子单（PENDING 即预占）+ 子单行
        long batchId = insertBatch(sellerId, buyerId, totalAmount);
        String batchNo = jdbc.queryForObject(
                "SELECT batch_no FROM red_flush_batch WHERE id = ?", String.class, batchId);
        List<BatchItemView> itemViews = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BlueInvoiceRow blue = blues.get(i);
            long requestId = redFlushService.insertRequest(blue, "PARTIAL", itemAmounts.get(i), batchId);
            String requestNo = jdbc.queryForObject(
                    "SELECT request_no FROM red_flush_request WHERE id = ?", String.class, requestId);
            List<RedFlushLineView> lineViews = new ArrayList<>();
            for (RedLineRequest line : items.get(i).lines()) {
                Long blueLineId = jdbc.queryForObject(
                        "SELECT id FROM invoice_line WHERE invoice_id = ? AND line_no = ?",
                        Long.class, blue.id(), line.lineNo());
                jdbc.update(
                        "INSERT INTO red_flush_line (request_id, blue_line_id, line_no, amount)"
                                + " VALUES (?, ?, ?, ?)",
                        requestId, blueLineId, line.lineNo(), line.amount());
                lineViews.add(new RedFlushLineView(line.lineNo(), line.amount()));
            }
            itemViews.add(new BatchItemView(requestNo, blue.invoiceNo(), itemAmounts.get(i),
                    "PENDING", null, lineViews));
        }
        CreateBatchResponse response = new CreateBatchResponse(batchNo, "PENDING", 1, totalAmount, itemViews);
        idempotencyService.complete(ENDPOINT_BATCH_CREATE, request.requestId(), 200, response);
        return response;
    }

    /**
     * 购销双方确认整批（幂等）。单方同意只留意见；双方均同意才在一个事务生成全部红票、
     * 消费预占并恢复对应额度；任一方拒绝则整批终止、释放全部占用。
     */
    public BatchConfirmResponse confirm(String batchNo, BatchConfirmRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.side() == null || !List.of("SELLER", "BUYER").contains(request.side())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "side 须为 SELLER 或 BUYER");
        }
        if (request.opinion() == null || !List.of("AGREE", "REJECT").contains(request.opinion())) {
            throw ApiException.badRequest("VALIDATION_FAILED", "opinion 须为 AGREE 或 REJECT");
        }
        if (request.expectedVersion() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "expectedVersion 不能为空");
        }
        ConfirmCommand command = new ConfirmCommand(request.requestId(), batchNo, request.side(),
                request.opinion(), request.expectedVersion());
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_BATCH_CONFIRM, command);
        var existing = idempotencyService.find(ENDPOINT_BATCH_CONFIRM, request.requestId());
        if (existing.isPresent()) {
            return deserializeConfirm(idempotencyService
                    .replayOrReject(ENDPOINT_BATCH_CONFIRM, command, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doConfirm(command, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeConfirm(idempotencyService
                    .replayOrReject(ENDPOINT_BATCH_CONFIRM, command, request.requestId()).body());
        }
    }

    private BatchConfirmResponse doConfirm(ConfirmCommand command, String fingerprint) {
        idempotencyService.begin(ENDPOINT_BATCH_CONFIRM, command.requestId(), fingerprint);
        BatchRow batch = lockBatchByNo(command.batchNo());
        if ("ISSUED".equals(batch.status())) {
            // 已生效：幂等返回当前状态，不重复开红票、不重复恢复额度
            BatchConfirmResponse response = new BatchConfirmResponse(command.batchNo(), "ISSUED",
                    batch.version(), creditService.computeAvailable(batch.sellerId()));
            idempotencyService.complete(ENDPOINT_BATCH_CONFIRM, command.requestId(), 200, response);
            return response;
        }
        if (!"PENDING".equals(batch.status())) {
            throw ApiException.conflict("BATCH_CLOSED",
                    "整批已终止或撤回，不再接受确认: " + command.batchNo());
        }
        if (command.expectedVersion() != batch.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "整批版本过期：期望 " + command.expectedVersion() + "，当前 " + batch.version());
        }
        jdbc.update(
                "INSERT INTO red_flush_batch_confirmation (batch_id, side, opinion, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                batch.id(), command.side(), command.opinion(), Timestamp.from(clock.instant()));
        int newVersion = batch.version() + 1;
        BatchConfirmResponse response;
        if ("REJECT".equals(command.opinion())) {
            // 任一方拒绝：整批终止，释放全部预占
            releaseChildren(batch.id());
            updateBatch(batch.id(), "TERMINATED", newVersion,
                    batch.sellerConfirmed(), batch.buyerConfirmed());
            response = new BatchConfirmResponse(command.batchNo(), "TERMINATED", newVersion,
                    creditService.computeAvailable(batch.sellerId()));
        } else {
            boolean sellerConfirmed = batch.sellerConfirmed() || "SELLER".equals(command.side());
            boolean buyerConfirmed = batch.buyerConfirmed() || "BUYER".equals(command.side());
            if (sellerConfirmed && buyerConfirmed) {
                // 双方均同意：一个事务开具全部红票、消费预占并恢复对应额度
                List<String> childNos = jdbc.queryForList(
                        "SELECT request_no FROM red_flush_request"
                                + " WHERE batch_id = ? AND status = 'PENDING' ORDER BY id",
                        String.class, batch.id());
                for (String childNo : childNos) {
                    redFlushService.issueControlledRequest(childNo);
                }
                updateBatch(batch.id(), "ISSUED", newVersion, true, true);
                response = new BatchConfirmResponse(command.batchNo(), "ISSUED", newVersion,
                        creditService.computeAvailable(batch.sellerId()));
            } else {
                // 单方同意只留意见
                updateBatch(batch.id(), "PENDING", newVersion, sellerConfirmed, buyerConfirmed);
                response = new BatchConfirmResponse(command.batchNo(), "PENDING", newVersion,
                        creditService.computeAvailable(batch.sellerId()));
            }
        }
        idempotencyService.complete(ENDPOINT_BATCH_CONFIRM, command.requestId(), 200, response);
        return response;
    }

    /**
     * 撤回整批（幂等）。仅未生效（PENDING）批可撤回，撤回释放全部预占；生效批不可撤回。
     */
    public BatchWithdrawResponse withdraw(String batchNo, BatchWithdrawRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.expectedVersion() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "expectedVersion 不能为空");
        }
        WithdrawCommand command = new WithdrawCommand(request.requestId(), batchNo,
                request.expectedVersion());
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_BATCH_WITHDRAW, command);
        var existing = idempotencyService.find(ENDPOINT_BATCH_WITHDRAW, request.requestId());
        if (existing.isPresent()) {
            return deserializeWithdraw(idempotencyService
                    .replayOrReject(ENDPOINT_BATCH_WITHDRAW, command, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doWithdraw(command, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeWithdraw(idempotencyService
                    .replayOrReject(ENDPOINT_BATCH_WITHDRAW, command, request.requestId()).body());
        }
    }

    private BatchWithdrawResponse doWithdraw(WithdrawCommand command, String fingerprint) {
        idempotencyService.begin(ENDPOINT_BATCH_WITHDRAW, command.requestId(), fingerprint);
        BatchRow batch = lockBatchByNo(command.batchNo());
        if ("ISSUED".equals(batch.status())) {
            throw ApiException.conflict("BATCH_ALREADY_EFFECTIVE",
                    "生效批不可撤回: " + command.batchNo());
        }
        if (!"PENDING".equals(batch.status())) {
            throw ApiException.conflict("BATCH_CLOSED",
                    "整批已终止或撤回: " + command.batchNo());
        }
        if (command.expectedVersion() != batch.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "整批版本过期：期望 " + command.expectedVersion() + "，当前 " + batch.version());
        }
        releaseChildren(batch.id());
        int newVersion = batch.version() + 1;
        updateBatch(batch.id(), "WITHDRAWN", newVersion, batch.sellerConfirmed(), batch.buyerConfirmed());
        BatchWithdrawResponse response = new BatchWithdrawResponse(command.batchNo(), "WITHDRAWN", newVersion);
        idempotencyService.complete(ENDPOINT_BATCH_WITHDRAW, command.requestId(), 200, response);
        return response;
    }

    /**
     * 查询整批：子单、预占、双方意见与额度勾稽，重复查询结果稳定。
     */
    public BatchView getBatch(String batchNo) {
        BatchRow batch = findBatchByNo(batchNo);
        List<BatchItemView> items = new ArrayList<>();
        jdbc.query(
                "SELECT id, request_no, blue_invoice_no, total_amount, status, red_invoice_no"
                        + " FROM red_flush_request WHERE batch_id = ? ORDER BY id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    List<RedFlushLineView> lines = jdbc.query(
                            "SELECT line_no, amount FROM red_flush_line WHERE request_id = ?"
                                    + " ORDER BY line_no",
                            (rs2, rowNum) -> new RedFlushLineView(rs2.getInt("line_no"),
                                    rs2.getBigDecimal("amount")),
                            rs.getLong("id"));
                    items.add(new BatchItemView(rs.getString("request_no"),
                            rs.getString("blue_invoice_no"), rs.getBigDecimal("total_amount"),
                            rs.getString("status"), rs.getString("red_invoice_no"), lines));
                },
                batch.id());
        List<BatchConfirmationView> confirmations = jdbc.query(
                "SELECT side, opinion, created_at FROM red_flush_batch_confirmation"
                        + " WHERE batch_id = ? ORDER BY id",
                (rs, rowNum) -> new BatchConfirmationView(rs.getString("side"), rs.getString("opinion"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                batch.id());
        BigDecimal reserved = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM red_flush_request"
                        + " WHERE batch_id = ? AND status = 'PENDING'",
                BigDecimal.class, batch.id());
        BigDecimal totalLimit = jdbc.query(
                        "SELECT total_limit FROM credit_account WHERE seller_id = ?",
                        (rs, rowNum) -> rs.getBigDecimal("total_limit"), batch.sellerId())
                .stream().findFirst().orElse(null);
        BatchCreditView credit = new BatchCreditView(batch.sellerId(), totalLimit,
                creditService.computeAvailable(batch.sellerId()),
                reserved == null ? BigDecimal.ZERO : reserved);
        return new BatchView(batch.batchNo(), batch.sellerId(), batch.buyerId(), batch.totalAmount(),
                batch.status(), batch.version(), batch.sellerConfirmed(), batch.buyerConfirmed(),
                items, confirmations, credit);
    }

    private void validateCreate(CreateBatchRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.items() == null || request.items().size() < 2 || request.items().size() > 10) {
            throw ApiException.badRequest("VALIDATION_FAILED", "一批须包含 2 至 10 张不同蓝票");
        }
        Set<String> invoiceNos = new HashSet<>();
        for (BatchItemRequest item : request.items()) {
            if (item.blueInvoiceNo() == null || item.blueInvoiceNo().isBlank()) {
                throw ApiException.badRequest("VALIDATION_FAILED", "blueInvoiceNo 不能为空");
            }
            if (!invoiceNos.add(item.blueInvoiceNo())) {
                throw ApiException.badRequest("VALIDATION_FAILED",
                        "批内蓝票不得重复: " + item.blueInvoiceNo());
            }
            if (item.lines() == null || item.lines().isEmpty()) {
                throw ApiException.badRequest("VALIDATION_FAILED",
                        "每票须给出红冲行: " + item.blueInvoiceNo());
            }
            Set<Integer> lineNos = new HashSet<>();
            for (RedLineRequest line : item.lines()) {
                if (line.lineNo() == null) {
                    throw ApiException.badRequest("VALIDATION_FAILED", "行号不能为空");
                }
                if (!lineNos.add(line.lineNo())) {
                    throw ApiException.badRequest("VALIDATION_FAILED",
                            "同一票内行号不得重复: " + line.lineNo());
                }
                if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0
                        || line.amount().scale() > 2) {
                    throw ApiException.badRequest("VALIDATION_FAILED",
                            "行红冲金额须为正数且最多两位小数");
                }
            }
        }
    }

    /**
     * 释放整批全部预占：受控子单置为 CANCELLED，不再占用行可红冲余额。
     */
    private void releaseChildren(long batchId) {
        jdbc.update("UPDATE red_flush_request SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE batch_id = ? AND status = 'PENDING'", batchId);
    }

    private void updateBatch(long batchId, String status, int version,
                             boolean sellerConfirmed, boolean buyerConfirmed) {
        jdbc.update("UPDATE red_flush_batch SET status = ?, version = ?, seller_confirmed = ?,"
                        + " buyer_confirmed = ?, updated_at = ? WHERE id = ?",
                status, version, sellerConfirmed, buyerConfirmed,
                Timestamp.from(clock.instant()), batchId);
    }

    private long insertBatch(String sellerId, String buyerId, BigDecimal totalAmount) {
        String tempNo = "TMP-" + java.util.UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "INSERT INTO red_flush_batch (batch_no, seller_id, buyer_id, total_amount, status,"
                        + " version, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', 1, ?, ?)",
                tempNo, sellerId, buyerId, totalAmount, now, now);
        Long id = jdbc.queryForObject("SELECT id FROM red_flush_batch WHERE batch_no = ?",
                Long.class, tempNo);
        String batchNo = "RFB" + String.format("%08d", id);
        jdbc.update("UPDATE red_flush_batch SET batch_no = ? WHERE id = ?", batchNo, id);
        return id;
    }

    /**
     * 按票号加行级悲观锁读取蓝票（须在事务内调用）；不存在时整批 422。
     */
    private BlueInvoiceRow lockBlue(String invoiceNo) {
        return jdbc.query(
                        "SELECT id, invoice_no, seller_id, buyer_id, buyer_booked, total_amount,"
                                + " red_flushed_amount, status FROM invoice"
                                + " WHERE invoice_no = ? AND kind = 'BLUE' FOR UPDATE",
                        (rs, rowNum) -> new BlueInvoiceRow(rs.getLong("id"), rs.getString("invoice_no"),
                                rs.getString("seller_id"), rs.getString("buyer_id"),
                                rs.getBoolean("buyer_booked"), rs.getBigDecimal("total_amount"),
                                rs.getBigDecimal("red_flushed_amount"), rs.getString("status")),
                        invoiceNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("INVOICE_NOT_FOUND",
                        "蓝票不存在: " + invoiceNo));
    }

    private BatchRow lockBatchByNo(String batchNo) {
        return jdbc.query(
                        "SELECT id, batch_no, seller_id, buyer_id, total_amount, status, version,"
                                + " seller_confirmed, buyer_confirmed"
                                + " FROM red_flush_batch WHERE batch_no = ? FOR UPDATE",
                        (rs, rowNum) -> mapBatch(rs), batchNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "整批不存在: " + batchNo));
    }

    private BatchRow findBatchByNo(String batchNo) {
        return jdbc.query(
                        "SELECT id, batch_no, seller_id, buyer_id, total_amount, status, version,"
                                + " seller_confirmed, buyer_confirmed"
                                + " FROM red_flush_batch WHERE batch_no = ?",
                        (rs, rowNum) -> mapBatch(rs), batchNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "整批不存在: " + batchNo));
    }

    private BatchRow mapBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchRow(rs.getLong("id"), rs.getString("batch_no"), rs.getString("seller_id"),
                rs.getString("buyer_id"), rs.getBigDecimal("total_amount"), rs.getString("status"),
                rs.getInt("version"), rs.getBoolean("seller_confirmed"),
                rs.getBoolean("buyer_confirmed"));
    }

    private CreateBatchResponse deserializeCreate(String body) {
        try {
            return objectMapper.readValue(body, CreateBatchResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private BatchConfirmResponse deserializeConfirm(String body) {
        try {
            return objectMapper.readValue(body, BatchConfirmResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private BatchWithdrawResponse deserializeWithdraw(String body) {
        try {
            return objectMapper.readValue(body, BatchWithdrawResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 整批内部行视图。
     */
    record BatchRow(long id, String batchNo, String sellerId, String buyerId, BigDecimal totalAmount,
                    String status, int version, boolean sellerConfirmed, boolean buyerConfirmed) {
    }

    /**
     * 整批内单票红冲项。
     */
    public record BatchItemRequest(String blueInvoiceNo, List<RedLineRequest> lines) {
    }

    /**
     * 整批受理请求（幂等指纹覆盖全部字段）。
     */
    public record CreateBatchRequest(String requestId, List<BatchItemRequest> items) {
    }

    /**
     * 整批确认请求。expectedVersion 为乐观锁版本，过期 409。
     */
    public record BatchConfirmRequest(String requestId, String side, String opinion,
                                      Integer expectedVersion) {
    }

    /**
     * 整批撤回请求。
     */
    public record BatchWithdrawRequest(String requestId, Integer expectedVersion) {
    }

    /**
     * 整批确认命令（幂等指纹覆盖 requestId、批号与全部业务字段）。
     */
    record ConfirmCommand(String requestId, String batchNo, String side, String opinion,
                          Integer expectedVersion) {
    }

    /**
     * 整批撤回命令（幂等指纹覆盖 requestId、批号与全部业务字段）。
     */
    record WithdrawCommand(String requestId, String batchNo, Integer expectedVersion) {
    }

    /**
     * 整批受理响应。
     */
    public record CreateBatchResponse(String batchNo, String status, Integer version,
                                      BigDecimal totalAmount, List<BatchItemView> items) {
    }

    /**
     * 整批确认响应。
     */
    public record BatchConfirmResponse(String batchNo, String status, Integer version,
                                       BigDecimal availableLimit) {
    }

    /**
     * 整批撤回响应。
     */
    public record BatchWithdrawResponse(String batchNo, String status, Integer version) {
    }

    /**
     * 批内受控子单视图。
     */
    public record BatchItemView(String requestNo, String blueInvoiceNo, BigDecimal amount,
                                String status, String redInvoiceNo, List<RedFlushLineView> lines) {
    }

    /**
     * 整批意见轨迹视图。createdAt 为 UTC 时间。
     */
    public record BatchConfirmationView(String side, String opinion, LocalDateTime createdAt) {
    }

    /**
     * 额度勾稽视图：总额度、实时可用额度与本批当前预占合计。
     */
    public record BatchCreditView(String sellerId, BigDecimal totalLimit, BigDecimal availableLimit,
                                  BigDecimal reservedAmount) {
    }

    /**
     * 整批详情视图：子单、预占、意见与额度勾稽。
     */
    public record BatchView(String batchNo, String sellerId, String buyerId, BigDecimal totalAmount,
                            String status, Integer version, boolean sellerConfirmed,
                            boolean buyerConfirmed, List<BatchItemView> items,
                            List<BatchConfirmationView> confirmations, BatchCreditView credit) {
    }
}
