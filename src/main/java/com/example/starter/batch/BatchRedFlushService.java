package com.example.starter.batch;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.common.ApiException;
import com.example.starter.credit.CreditService;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.redflush.RedFlushService;
import com.example.starter.redflush.RedFlushService.ControlledChild;
import com.example.starter.redflush.RedFlushService.RedFlushLineView;
import com.example.starter.redflush.RedFlushService.RedLineRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 整批红冲服务：同一销购双方的 2 至 10 张不同蓝票一次受理、逐行预占、双方确认整批生效。
 * 规则：受理时复用单票红冲的逐行校验（票据可红冲、行余额、在途争议），预占全部行的
 * 可红冲余额，任一不符整批 422 且事务回滚，不留下子单或占用；批内每票建立受控子单，
 * 预占与普通红冲、差异登记共享行互斥，并发受理不能合计超过余额；预占本身不恢复授信，
 * 原蓝票与归档证据不改。购销双方分别确认整批，单方同意只留意见；双方均同意才在一个
 * 事务生成全部红票、消费预占并恢复对应额度；任一方拒绝则整批终止、释放全部占用；
 * 尚未生效的批可撤回，生效批不可撤回；确认与撤回竞争只能全部生效或全部撤回。
 * 批版本自 1 起，修改带 expectedVersion，成功加 1、过期 409；新增写入按 requestId
 * 幂等，同键同参重放原响应、改参 409、失败不占键；业务、版本与幂等同事务。
 * 金额为合成规则，批相关时间统一 UTC，不连接税务平台。
 */
@Service
public class BatchRedFlushService {

    static final String ENDPOINT_BATCH_CREATE = "RED_FLUSH_BATCH_CREATE";
    static final String ENDPOINT_BATCH_CONFIRM = "RED_FLUSH_BATCH_CONFIRM";
    static final String ENDPOINT_BATCH_WITHDRAW = "RED_FLUSH_BATCH_WITHDRAW";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RedFlushService redFlushService;
    private final CreditService creditService;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public BatchRedFlushService(JdbcTemplate jdbc, Clock clock, RedFlushService redFlushService,
                                CreditService creditService, IdempotencyService idempotencyService,
                                TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.redFlushService = redFlushService;
        this.creditService = creditService;
        this.idempotencyService = idempotencyService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 受理整批红冲（幂等）。逐行检查并预占全部行余额；任一不符整批 422，
     * 事务回滚不留下子单或占用，失败不占幂等键。
     */
    public CreateBatchResponse createBatch(CreateBatchRequest request) {
        validateCreate(request);
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_BATCH_CREATE, request);
        var existing = idempotencyService.find(ENDPOINT_BATCH_CREATE, request.requestId());
        if (existing.isPresent()) {
            return deserializeCreate(
                    idempotencyService.replayOrReject(ENDPOINT_BATCH_CREATE, request, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doCreateBatch(request, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发同键：首次请求已提交，重放其结果
            return deserializeCreate(
                    idempotencyService.replayOrReject(ENDPOINT_BATCH_CREATE, request, request.requestId()).body());
        }
    }

    private CreateBatchResponse doCreateBatch(CreateBatchRequest request, String fingerprint) {
        idempotencyService.begin(ENDPOINT_BATCH_CREATE, request.requestId(), fingerprint);
        BigDecimal totalAmount = request.items().stream()
                .flatMap(item -> item.lines().stream())
                .map(RedLineRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long batchId = insertBatch(request, totalAmount);
        String batchNo = jdbc.queryForObject(
                "SELECT batch_no FROM red_flush_batch WHERE id = ?", String.class, batchId);
        // 按票号排序逐票加锁，保证并发整批受理锁序一致、不发生死锁
        List<BatchItemRequest> ordered = new ArrayList<>(request.items());
        ordered.sort(Comparator.comparing(BatchItemRequest::blueInvoiceNo));
        List<BatchItemView> items = new ArrayList<>();
        for (BatchItemRequest item : ordered) {
            ControlledChild child;
            try {
                child = redFlushService.createControlledChild(item.blueInvoiceNo(), item.lines(), batchId);
            } catch (ApiException e) {
                // 受理阶段任一不符整批 422；事务回滚保证不留下子单或占用
                throw ApiException.unprocessable(e.getCode(), e.getMessage());
            }
            if (!request.sellerId().equals(child.sellerId()) || !request.buyerId().equals(child.buyerId())) {
                throw ApiException.unprocessable("PARTY_MISMATCH",
                        "蓝票 " + item.blueInvoiceNo() + " 购销双方与整批不一致");
            }
            items.add(new BatchItemView(child.requestNo(), child.blueInvoiceNo(), child.amount(),
                    "PENDING", null, toLineViews(item.lines())));
        }
        CreateBatchResponse response = new CreateBatchResponse(batchNo, "PENDING", 1,
                request.sellerId(), request.buyerId(), totalAmount, items);
        idempotencyService.complete(ENDPOINT_BATCH_CREATE, request.requestId(), 200, response);
        return response;
    }

    /**
     * 购销双方确认整批（幂等）。单方同意只留意见；双方均同意才在一个事务生成全部
     * 红票、消费预占并恢复对应额度；任一方拒绝则整批终止、释放全部占用。
     */
    public ConfirmBatchResponse confirmBatch(String batchNo, ConfirmBatchRequest request) {
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
            return deserializeConfirm(idempotencyService.replayOrReject(
                    ENDPOINT_BATCH_CONFIRM, command, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doConfirmBatch(command, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeConfirm(idempotencyService.replayOrReject(
                    ENDPOINT_BATCH_CONFIRM, command, request.requestId()).body());
        }
    }

    private ConfirmBatchResponse doConfirmBatch(ConfirmCommand command, String fingerprint) {
        idempotencyService.begin(ENDPOINT_BATCH_CONFIRM, command.requestId(), fingerprint);
        BatchRow batch = lockBatchByNo(command.batchNo());
        if ("ISSUED".equals(batch.status())) {
            throw ApiException.conflict("BATCH_ALREADY_ISSUED", "整批已生效，不可再确认: " + command.batchNo());
        }
        if ("TERMINATED".equals(batch.status())) {
            throw ApiException.conflict("BATCH_TERMINATED", "整批已终止: " + command.batchNo());
        }
        requireVersion(batch, command.expectedVersion());
        String recorded = opinionOf(batch.id(), command.side());
        if (recorded != null) {
            if (recorded.equals(command.opinion())) {
                // 同方同意见重复提交：幂等成功，不重复推进版本
                ConfirmBatchResponse response = new ConfirmBatchResponse(batch.batchNo(), batch.status(),
                        batch.version(), availableOrNull(batch.sellerId()));
                idempotencyService.complete(ENDPOINT_BATCH_CONFIRM, command.requestId(), 200, response);
                return response;
            }
            throw ApiException.conflict("OPINION_CONFLICT",
                    "该方已登记不同意见: " + command.side());
        }
        insertOpinion(batch.id(), command.side(), command.opinion());
        ConfirmBatchResponse response;
        if ("REJECT".equals(command.opinion())) {
            // 任一方拒绝：整批终止、释放全部占用
            terminateBatch(batch);
            response = new ConfirmBatchResponse(batch.batchNo(), "TERMINATED", batch.version() + 1,
                    availableOrNull(batch.sellerId()));
        } else if (bothSidesAgreed(batch.id())) {
            // 双方均同意：一个事务生成全部红票、消费预占并恢复对应额度
            List<String> childRequestNos = jdbc.queryForList(
                    "SELECT request_no FROM red_flush_request"
                            + " WHERE batch_id = ? AND status = 'PENDING' ORDER BY id",
                    String.class, batch.id());
            for (String childRequestNo : childRequestNos) {
                redFlushService.issueControlledChild(childRequestNo);
            }
            jdbc.update("UPDATE red_flush_batch SET status = 'ISSUED', version = version + 1,"
                    + " updated_at = ? WHERE id = ?", Timestamp.from(clock.instant()), batch.id());
            response = new ConfirmBatchResponse(batch.batchNo(), "ISSUED", batch.version() + 1,
                    availableOrNull(batch.sellerId()));
        } else {
            // 单方同意只留意见，不生效
            jdbc.update("UPDATE red_flush_batch SET version = version + 1, updated_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant()), batch.id());
            response = new ConfirmBatchResponse(batch.batchNo(), "PENDING", batch.version() + 1,
                    availableOrNull(batch.sellerId()));
        }
        idempotencyService.complete(ENDPOINT_BATCH_CONFIRM, command.requestId(), 200, response);
        return response;
    }

    /**
     * 撤回整批（幂等）。仅未生效（PENDING）的批可撤回；撤回即整批终止、释放全部占用；
     * 生效批不可撤回。与确认竞争时按批行锁串行，只能全部生效或全部撤回。
     */
    public WithdrawBatchResponse withdrawBatch(String batchNo, WithdrawBatchRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.expectedVersion() == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "expectedVersion 不能为空");
        }
        WithdrawCommand command = new WithdrawCommand(request.requestId(), batchNo, request.expectedVersion());
        String fingerprint = idempotencyService.fingerprint(ENDPOINT_BATCH_WITHDRAW, command);
        var existing = idempotencyService.find(ENDPOINT_BATCH_WITHDRAW, request.requestId());
        if (existing.isPresent()) {
            return deserializeWithdraw(idempotencyService.replayOrReject(
                    ENDPOINT_BATCH_WITHDRAW, command, request.requestId()).body());
        }
        try {
            return transactionTemplate.execute(status -> doWithdrawBatch(command, fingerprint));
        } catch (DuplicateKeyException e) {
            return deserializeWithdraw(idempotencyService.replayOrReject(
                    ENDPOINT_BATCH_WITHDRAW, command, request.requestId()).body());
        }
    }

    private WithdrawBatchResponse doWithdrawBatch(WithdrawCommand command, String fingerprint) {
        idempotencyService.begin(ENDPOINT_BATCH_WITHDRAW, command.requestId(), fingerprint);
        BatchRow batch = lockBatchByNo(command.batchNo());
        if ("ISSUED".equals(batch.status())) {
            throw ApiException.conflict("BATCH_ALREADY_ISSUED", "生效批不可撤回: " + command.batchNo());
        }
        if ("TERMINATED".equals(batch.status())) {
            throw ApiException.conflict("BATCH_TERMINATED", "整批已终止: " + command.batchNo());
        }
        requireVersion(batch, command.expectedVersion());
        terminateBatch(batch);
        WithdrawBatchResponse response = new WithdrawBatchResponse(batch.batchNo(), "TERMINATED",
                batch.version() + 1);
        idempotencyService.complete(ENDPOINT_BATCH_WITHDRAW, command.requestId(), 200, response);
        return response;
    }

    /**
     * 查询整批：子单、预占、双方意见与额度勾稽，重复查询结果稳定。
     * 额度勾稽：preOccupiedAmount 为仍在途的预占合计，restoredAmount 为本批已开红票
     * 恢复的额度合计，availableLimit 为销方当前可用额度。
     */
    public BatchView getBatch(String batchNo) {
        BatchRow batch = findBatchByNo(batchNo);
        List<BatchItemView> items = jdbc.query(
                        "SELECT request_no, blue_invoice_no, total_amount, status, red_invoice_no"
                                + " FROM red_flush_request WHERE batch_id = ? ORDER BY id",
                        (rs, rowNum) -> new BatchItemView(rs.getString("request_no"),
                                rs.getString("blue_invoice_no"), rs.getBigDecimal("total_amount"),
                                rs.getString("status"), rs.getString("red_invoice_no"), List.of()),
                        batch.id())
                .stream()
                .map(item -> new BatchItemView(item.requestNo(), item.blueInvoiceNo(), item.amount(),
                        item.status(), item.redInvoiceNo(), childLines(item.requestNo())))
                .toList();
        List<BatchOpinionView> opinions = jdbc.query(
                "SELECT side, opinion, created_at FROM red_flush_batch_opinion"
                        + " WHERE batch_id = ? ORDER BY id",
                (rs, rowNum) -> new BatchOpinionView(rs.getString("side"), rs.getString("opinion"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                batch.id());
        BigDecimal preOccupied = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM red_flush_request"
                        + " WHERE batch_id = ? AND status = 'PENDING'",
                BigDecimal.class, batch.id());
        BigDecimal restored = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM red_flush_request"
                        + " WHERE batch_id = ? AND status = 'ISSUED'",
                BigDecimal.class, batch.id());
        BatchTimestamps timestamps = jdbc.query(
                        "SELECT created_at, updated_at FROM red_flush_batch WHERE id = ?",
                        (rs, rowNum) -> new BatchTimestamps(rs.getTimestamp("created_at").toLocalDateTime(),
                                rs.getTimestamp("updated_at").toLocalDateTime()),
                        batch.id())
                .get(0);
        return new BatchView(batch.batchNo(), batch.sellerId(), batch.buyerId(), batch.totalAmount(),
                batch.status(), batch.version(),
                preOccupied == null ? BigDecimal.ZERO : preOccupied,
                restored == null ? BigDecimal.ZERO : restored,
                availableOrNull(batch.sellerId()), items, opinions,
                timestamps.createdAt(), timestamps.updatedAt());
    }

    private void validateCreate(CreateBatchRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "requestId 不能为空");
        }
        if (request.sellerId() == null || request.sellerId().isBlank()
                || request.buyerId() == null || request.buyerId().isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "sellerId/buyerId 不能为空");
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
                        "一批内蓝票不得重复: " + item.blueInvoiceNo());
            }
            if (item.lines() == null || item.lines().isEmpty()) {
                throw ApiException.badRequest("VALIDATION_FAILED", "每票须给出行号及金额");
            }
            Set<Integer> lineNos = new HashSet<>();
            for (RedLineRequest line : item.lines()) {
                if (line.lineNo() == null) {
                    throw ApiException.badRequest("VALIDATION_FAILED", "lineNo 不能为空");
                }
                if (!lineNos.add(line.lineNo())) {
                    throw ApiException.badRequest("VALIDATION_FAILED",
                            "同一票内行号不得重复: " + line.lineNo());
                }
                if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw ApiException.badRequest("VALIDATION_FAILED", "行红冲金额必须为正数");
                }
                if (line.amount().stripTrailingZeros().scale() > 2) {
                    throw ApiException.badRequest("VALIDATION_FAILED", "行红冲金额最多两位小数");
                }
            }
        }
    }

    private void requireVersion(BatchRow batch, int expectedVersion) {
        if (batch.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "批版本已过期：当前 " + batch.version() + "，期望 " + expectedVersion);
        }
    }

    /**
     * 整批终止：释放在途受控子单预占，批状态置 TERMINATED 并推进版本（须在事务内执行）。
     */
    private void terminateBatch(BatchRow batch) {
        redFlushService.releaseControlledChildren(batch.id());
        jdbc.update("UPDATE red_flush_batch SET status = 'TERMINATED', version = version + 1,"
                + " updated_at = ? WHERE id = ?", Timestamp.from(clock.instant()), batch.id());
    }

    private String opinionOf(long batchId, String side) {
        return jdbc.query(
                        "SELECT opinion FROM red_flush_batch_opinion WHERE batch_id = ? AND side = ?",
                        (rs, rowNum) -> rs.getString("opinion"), batchId, side)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean bothSidesAgreed(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_batch_opinion"
                        + " WHERE batch_id = ? AND opinion = 'AGREE'",
                Integer.class, batchId);
        return count != null && count == 2;
    }

    private void insertOpinion(long batchId, String side, String opinion) {
        jdbc.update("INSERT INTO red_flush_batch_opinion (batch_id, side, opinion, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                batchId, side, opinion, Timestamp.from(clock.instant()));
    }

    private List<RedFlushLineView> childLines(String requestNo) {
        return jdbc.query(
                "SELECT rl.line_no, rl.amount FROM red_flush_line rl"
                        + " JOIN red_flush_request r ON rl.request_id = r.id"
                        + " WHERE r.request_no = ? ORDER BY rl.line_no",
                (rs, rowNum) -> new RedFlushLineView(rs.getInt("line_no"), rs.getBigDecimal("amount")),
                requestNo);
    }

    private List<RedFlushLineView> toLineViews(List<RedLineRequest> lines) {
        return lines.stream()
                .map(line -> new RedFlushLineView(line.lineNo(), line.amount()))
                .toList();
    }

    /**
     * 销方当前可用额度；无授信账户时返回 null（额度勾稽查询不因此失败）。
     */
    private BigDecimal availableOrNull(String sellerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_account WHERE seller_id = ?", Integer.class, sellerId);
        if (count == null || count == 0) {
            return null;
        }
        return creditService.computeAvailable(sellerId);
    }

    private long insertBatch(CreateBatchRequest request, BigDecimal totalAmount) {
        String tempNo = "TMP-" + java.util.UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "INSERT INTO red_flush_batch (batch_no, seller_id, buyer_id, total_amount, status, version,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', 1, ?, ?)",
                tempNo, request.sellerId(), request.buyerId(), totalAmount, now, now);
        Long id = jdbc.queryForObject("SELECT id FROM red_flush_batch WHERE batch_no = ?",
                Long.class, tempNo);
        String batchNo = "RFB" + String.format("%08d", id);
        jdbc.update("UPDATE red_flush_batch SET batch_no = ? WHERE id = ?", batchNo, id);
        return id;
    }

    private BatchRow lockBatchByNo(String batchNo) {
        return jdbc.query(
                        "SELECT id, batch_no, seller_id, buyer_id, total_amount, status, version"
                                + " FROM red_flush_batch WHERE batch_no = ? FOR UPDATE",
                        (rs, rowNum) -> mapBatch(rs), batchNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "整批不存在: " + batchNo));
    }

    private BatchRow findBatchByNo(String batchNo) {
        return jdbc.query(
                        "SELECT id, batch_no, seller_id, buyer_id, total_amount, status, version"
                                + " FROM red_flush_batch WHERE batch_no = ?",
                        (rs, rowNum) -> mapBatch(rs), batchNo)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "整批不存在: " + batchNo));
    }

    private BatchRow mapBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchRow(rs.getLong("id"), rs.getString("batch_no"), rs.getString("seller_id"),
                rs.getString("buyer_id"), rs.getBigDecimal("total_amount"), rs.getString("status"),
                rs.getInt("version"));
    }

    private CreateBatchResponse deserializeCreate(String body) {
        try {
            return objectMapper.readValue(body, CreateBatchResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ConfirmBatchResponse deserializeConfirm(String body) {
        try {
            return objectMapper.readValue(body, ConfirmBatchResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private WithdrawBatchResponse deserializeWithdraw(String body) {
        try {
            return objectMapper.readValue(body, WithdrawBatchResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 整批内部行视图。
     */
    record BatchRow(long id, String batchNo, String sellerId, String buyerId, BigDecimal totalAmount,
                    String status, int version) {
    }

    /**
     * 整批时间戳内部视图（UTC）。
     */
    record BatchTimestamps(LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /**
     * 整批确认命令（幂等指纹覆盖 requestId、批号、确认方、意见与期望版本）。
     */
    record ConfirmCommand(String requestId, String batchNo, String side, String opinion,
                          Integer expectedVersion) {
    }

    /**
     * 整批撤回命令（幂等指纹覆盖 requestId、批号与期望版本）。
     */
    record WithdrawCommand(String requestId, String batchNo, Integer expectedVersion) {
    }

    /**
     * 整批内单票请求项。
     */
    public record BatchItemRequest(String blueInvoiceNo, List<RedLineRequest> lines) {
    }

    /**
     * 整批受理请求（幂等指纹覆盖全部字段）。
     */
    public record CreateBatchRequest(String requestId, String sellerId, String buyerId,
                                     List<BatchItemRequest> items) {
    }

    /**
     * 整批子单视图。
     */
    public record BatchItemView(String requestNo, String blueInvoiceNo, BigDecimal amount, String status,
                                String redInvoiceNo, List<RedFlushLineView> lines) {
    }

    /**
     * 整批受理响应。版本自 1 起。
     */
    public record CreateBatchResponse(String batchNo, String status, Integer version, String sellerId,
                                      String buyerId, BigDecimal totalAmount, List<BatchItemView> items) {
    }

    /**
     * 整批确认请求。
     */
    public record ConfirmBatchRequest(String requestId, String side, String opinion,
                                      Integer expectedVersion) {
    }

    /**
     * 整批确认响应。
     */
    public record ConfirmBatchResponse(String batchNo, String status, Integer version,
                                       BigDecimal availableLimit) {
    }

    /**
     * 整批撤回请求。
     */
    public record WithdrawBatchRequest(String requestId, Integer expectedVersion) {
    }

    /**
     * 整批撤回响应。
     */
    public record WithdrawBatchResponse(String batchNo, String status, Integer version) {
    }

    /**
     * 整批意见视图。createdAt 为 UTC 时间。
     */
    public record BatchOpinionView(String side, String opinion, LocalDateTime createdAt) {
    }

    /**
     * 整批详情视图：子单、预占、意见与额度勾稽。createdAt/updatedAt 为 UTC 时间。
     */
    public record BatchView(String batchNo, String sellerId, String buyerId, BigDecimal totalAmount,
                            String status, Integer version, BigDecimal preOccupiedAmount,
                            BigDecimal restoredAmount, BigDecimal availableLimit,
                            List<BatchItemView> items, List<BatchOpinionView> opinions,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
