package com.example.starter.metro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.metro.MetroRepository.IdemRow;
import com.example.starter.metro.RefundRepository.AdjustmentBatchRow;
import com.example.starter.metro.RefundRepository.BatchDetailRow;
import com.example.starter.metro.RemittanceRepository.ReceiptRow;
import com.example.starter.metro.RemittanceRepository.StatementLineRow;
import com.example.starter.metro.RemittanceRepository.StatementRow;
import com.example.starter.metro.dto.RemittanceReceiptRequest;
import com.example.starter.metro.dto.RemittanceReceiptResponse;
import com.example.starter.metro.dto.RemittanceStatementOpenRequest;
import com.example.starter.metro.dto.RemittanceStatementOpenResponse;
import com.example.starter.metro.dto.RemittanceStatementView;
import com.example.starter.metro.dto.ReplayableResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 缴回对账业务服务：封存调账批次后建立缴回对账单（每批最多一单，逐线路应缴
 * 取本批负向调整金额绝对值之和并冻结全部来源明细），合成银行回执整笔入账。
 * 原退款分摊、双人确认与封账金额不重算，原清分与调账快照不可覆盖。
 *
 * <p>回执校验：分配合计须等于总额，每条为正且不超过本线路未缴余额；任一不符
 * 整笔零入账（事务回滚），所有线路同时更新。可分次、乱序到账，全部线路恰缴齐
 * 才完成；完成后新流水拒绝。银行流水号全局唯一：同流水同完整内容返回首次结果
 * 并绑定新的 requestId，换批、改时刻或金额 409。
 *
 * <p>写操作以 requestId 幂等：指纹为结构化 JSON，覆盖操作域与全部业务参数；
 * 同键同参重放首次冻结结果，同键异参 409，业务失败回滚不占键。
 */
@Service
public class RemittanceService {

    /** 幂等记录操作域。 */
    static final String SCOPE_STATEMENT_OPEN = "REMIT_STATEMENT_OPEN";
    static final String SCOPE_RECEIPT = "REMIT_RECEIPT";

    private final RemittanceRepository remittanceRepository;
    private final RefundRepository refundRepository;
    private final MetroRepository metroRepository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public RemittanceService(RemittanceRepository remittanceRepository,
                             RefundRepository refundRepository, MetroRepository metroRepository,
                             PlatformTransactionManager transactionManager,
                             ObjectMapper objectMapper) {
        this.remittanceRepository = remittanceRepository;
        this.refundRepository = refundRepository;
        this.metroRepository = metroRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 建立缴回对账单：仅非空已封存调账批次，每批最多一单，建立时冻结全部来源明细。 */
    public RemittanceStatementOpenResponse openStatement(RemittanceStatementOpenRequest request) {
        String fingerprint = fingerprint(SCOPE_STATEMENT_OPEN, List.of(request.requestId(),
                request.statementNo(), request.batchNo(), request.openTime().toString()));
        return idempotent(SCOPE_STATEMENT_OPEN, request.requestId(), fingerprint,
                RemittanceStatementOpenResponse.class, () -> doOpenStatement(request));
    }

    /** 合成银行回执入账：整笔入账或整笔零入账，全部线路同事务更新。 */
    public RemittanceReceiptResponse receipt(RemittanceReceiptRequest request) {
        String fingerprint = fingerprint(SCOPE_RECEIPT, receiptFingerprintParts(request, true));
        return idempotent(SCOPE_RECEIPT, request.requestId(), fingerprint,
                RemittanceReceiptResponse.class, () -> doReceipt(request));
    }

    /** 查询对账单：应缴、已缴、余额及流水分配链。 */
    public RemittanceStatementView statementView(String statementNo) {
        StatementRow statement = remittanceRepository.findStatementByNo(statementNo)
                .orElseThrow(() -> MetroException.notFound("REMIT_STATEMENT_NOT_FOUND",
                        "缴回对账单不存在: " + statementNo));
        List<RemittanceStatementView.LineBalance> lines = new ArrayList<>();
        for (StatementLineRow line : remittanceRepository.findLinesByStatement(statement.id())) {
            lines.add(new RemittanceStatementView.LineBalance(line.lineCode(), line.dueCents(),
                    line.paidCents(), line.dueCents() - line.paidCents()));
        }
        List<RemittanceStatementView.ReceiptView> receipts = new ArrayList<>();
        for (ReceiptRow receipt : remittanceRepository.findReceiptsByStatement(statement.id())) {
            List<RemittanceStatementView.LineAllocation> allocations = new ArrayList<>();
            for (var line : remittanceRepository.findReceiptLines(receipt.id())) {
                allocations.add(new RemittanceStatementView.LineAllocation(line.lineCode(),
                        line.amountCents()));
            }
            receipts.add(new RemittanceStatementView.ReceiptView(receipt.bankFlowNo(),
                    receipt.arrivedAt().toInstant(ZoneOffset.UTC), receipt.totalCents(),
                    allocations));
        }
        return new RemittanceStatementView(statement.statementNo(), statement.batchNo(),
                statement.status(), statement.dueTotalCents(), statement.paidTotalCents(),
                statement.dueTotalCents() - statement.paidTotalCents(), statement.version(),
                lines, receipts);
    }

    // ---- 建立对账单 ----

    private RemittanceStatementOpenResponse doOpenStatement(RemittanceStatementOpenRequest request) {
        AdjustmentBatchRow batch = refundRepository.findAdjustmentBatchByNo(request.batchNo())
                .orElseThrow(() -> MetroException.notFound("ADJ_BATCH_NOT_FOUND",
                        "调账批次不存在: " + request.batchNo()));
        if (!"SEALED".equals(batch.status())) {
            throw MetroException.unprocessable("REMIT_BATCH_NOT_SEALED",
                    "仅已封存调账批次可建立缴回对账单: " + request.batchNo());
        }
        List<BatchDetailRow> details = refundRepository.findBatchDetails(batch.id());
        if (details.isEmpty()) {
            throw MetroException.unprocessable("REMIT_BATCH_EMPTY",
                    "空调账批次不可建立缴回对账单: " + request.batchNo());
        }
        if (remittanceRepository.findStatementByBatchId(batch.id()).isPresent()) {
            throw MetroException.conflict("REMIT_STATEMENT_EXISTS",
                    "每批最多一单，该调账批次已建立对账单: " + request.batchNo());
        }
        if (remittanceRepository.findStatementByNo(request.statementNo()).isPresent()) {
            throw MetroException.conflict("DUPLICATE_STATEMENT_NO",
                    "对账单号重复: " + request.statementNo());
        }

        // 逐线路应缴额 = 本批负向调整金额绝对值之和（整数分）
        Map<String, Long> dueByLine = new LinkedHashMap<>();
        for (BatchDetailRow detail : details) {
            if (detail.amountCents() >= 0) {
                throw new IllegalStateException("调账批次明细非负向: " + detail.id());
            }
            dueByLine.merge(detail.lineCode(), -detail.amountCents(), Long::sum);
        }
        long dueTotal = dueByLine.values().stream().mapToLong(Long::longValue).sum();

        long statementId;
        try {
            statementId = remittanceRepository.insertStatement(request.statementNo(), batch.id(),
                    request.batchNo(), dueTotal, request.requestId(), LocalDateTime.now());
        } catch (DuplicateKeyException ex) {
            // 唯一约束兜底：单号重复或该批次已有对账单
            if (remittanceRepository.findStatementByNo(request.statementNo()).isPresent()) {
                throw MetroException.conflict("DUPLICATE_STATEMENT_NO",
                        "对账单号重复: " + request.statementNo());
            }
            throw MetroException.conflict("REMIT_STATEMENT_EXISTS",
                    "每批最多一单，该调账批次已建立对账单: " + request.batchNo());
        }
        // 冻结逐线路应缴快照与全部来源明细，后续查询可从明细复算应缴额
        dueByLine.forEach((line, due) ->
                remittanceRepository.insertStatementLine(statementId, line, due));
        for (BatchDetailRow detail : details) {
            remittanceRepository.insertSourceDetail(statementId, detail.adjustmentNo(),
                    detail.txnNo(), detail.lineCode(), detail.amountCents());
        }
        return new RemittanceStatementOpenResponse(request.statementNo(), request.batchNo(),
                "OPEN", dueTotal, dueByLine.size(), 1, false);
    }

    // ---- 回执入账 ----

    private RemittanceReceiptResponse doReceipt(RemittanceReceiptRequest request) {
        StatementRow statement = remittanceRepository.findStatementByNoForUpdate(request.statementNo())
                .orElseThrow(() -> MetroException.notFound("REMIT_STATEMENT_NOT_FOUND",
                        "缴回对账单不存在: " + request.statementNo()));

        // 银行流水号全局唯一：同流水同完整内容返回首次结果（由外层绑定新 requestId），
        // 换批、改时刻或金额 409（流水判重优先于批次一致性校验）
        String contentFingerprint = fingerprint("REMIT_RECEIPT_CONTENT",
                receiptFingerprintParts(request, false));
        Optional<ReceiptRow> existing = remittanceRepository.findReceiptByFlow(request.bankFlowNo());
        if (existing.isPresent()) {
            ReceiptRow committed = existing.get();
            if (!committed.contentFingerprint().equals(contentFingerprint)) {
                throw MetroException.conflict("BANK_FLOW_CONFLICT",
                        "银行流水号已存在但内容不一致（换批、改时刻或金额）: " + request.bankFlowNo());
            }
            return replayReceipt(committed);
        }

        if (!statement.batchNo().equals(request.batchNo())) {
            throw MetroException.unprocessable("REMIT_BATCH_MISMATCH",
                    "回执批次与对账单来源批次不一致，不同批次不得混用: " + request.batchNo());
        }

        if ("COMPLETED".equals(statement.status())) {
            throw MetroException.unprocessable("REMIT_STATEMENT_COMPLETED",
                    "对账单已全部缴齐，新流水拒绝: " + request.statementNo());
        }
        if (request.expectedVersion() != statement.version()) {
            throw versionConflict(request.expectedVersion(), statement.version());
        }

        List<StatementLineRow> lines = remittanceRepository.findLinesByStatementForUpdate(statement.id());
        Map<String, Long> dueByLine = new LinkedHashMap<>();
        Map<String, Long> paidByLine = new LinkedHashMap<>();
        for (StatementLineRow line : lines) {
            dueByLine.put(line.lineCode(), line.dueCents());
            paidByLine.put(line.lineCode(), line.paidCents());
        }

        // 分配合法性：1 至 10 条不同线路（ Bean 校验保证条数），线路须属于本对账单
        Map<String, Long> allocationByLine = new LinkedHashMap<>();
        for (RemittanceReceiptRequest.LineAllocation allocation : request.allocations()) {
            if (allocationByLine.putIfAbsent(allocation.lineCode(), allocation.amountCents()) != null) {
                throw MetroException.unprocessable("REMIT_LINE_DUPLICATE",
                        "同一回执内线路分配重复: " + allocation.lineCode());
            }
            if (!dueByLine.containsKey(allocation.lineCode())) {
                throw MetroException.unprocessable("REMIT_LINE_UNKNOWN",
                        "线路不属于本对账单: " + allocation.lineCode());
            }
        }
        long allocationTotal = allocationByLine.values().stream().mapToLong(Long::longValue).sum();
        if (allocationTotal != request.totalCents()) {
            throw MetroException.unprocessable("REMIT_TOTAL_MISMATCH",
                    "分配合计 " + allocationTotal + " 分不等于回执总额 " + request.totalCents()
                            + " 分，整笔零入账");
        }
        for (Map.Entry<String, Long> entry : allocationByLine.entrySet()) {
            long balance = dueByLine.get(entry.getKey()) - paidByLine.get(entry.getKey());
            if (entry.getValue() > balance) {
                throw MetroException.unprocessable("REMIT_OVERPAY",
                        "线路 " + entry.getKey() + " 分配 " + entry.getValue()
                                + " 分超过未缴余额 " + balance + " 分，整笔零入账");
            }
        }

        // 入账：响应先行构造并随凭据冻结，随后逐线路条件缴款与对账单版本推进，同事务提交
        long paidTotal = statement.paidTotalCents() + request.totalCents();
        boolean completed = lines.stream().allMatch(line ->
                paidByLine.get(line.lineCode()) + allocationByLine.getOrDefault(line.lineCode(), 0L)
                        == line.dueCents());
        List<RemittanceReceiptResponse.LineAllocation> allocations = allocationByLine.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new RemittanceReceiptResponse.LineAllocation(e.getKey(), e.getValue()))
                .toList();
        RemittanceReceiptResponse response = new RemittanceReceiptResponse(request.statementNo(),
                request.batchNo(), request.bankFlowNo(), request.arrivedAt(), request.totalCents(),
                allocations, paidTotal, statement.dueTotalCents() - paidTotal,
                completed ? "COMPLETED" : "OPEN", statement.version() + 1, false);

        long receiptId;
        try {
            receiptId = remittanceRepository.insertReceipt(statement.id(), request.bankFlowNo(),
                    LocalDateTime.ofInstant(request.arrivedAt(), ZoneOffset.UTC),
                    request.totalCents(), contentFingerprint, toJson(response), request.requestId());
        } catch (DuplicateKeyException ex) {
            // 并发同流水：唯一约束兜底，重读后按内容判重
            ReceiptRow committed = remittanceRepository.findReceiptByFlow(request.bankFlowNo())
                    .orElseThrow(() -> ex);
            if (!committed.contentFingerprint().equals(contentFingerprint)) {
                throw MetroException.conflict("BANK_FLOW_CONFLICT",
                        "银行流水号已存在但内容不一致（换批、改时刻或金额）: " + request.bankFlowNo());
            }
            return replayReceipt(committed);
        }
        for (RemittanceReceiptResponse.LineAllocation allocation : allocations) {
            remittanceRepository.insertReceiptLine(receiptId, allocation.lineCode(),
                    allocation.amountCents());
        }
        for (RemittanceReceiptResponse.LineAllocation allocation : allocations) {
            if (remittanceRepository.payLine(statement.id(), allocation.lineCode(),
                    allocation.amountCents()) != 1) {
                // 条件更新兜底：并发下余额不足，整笔零入账
                throw MetroException.unprocessable("REMIT_OVERPAY",
                        "线路 " + allocation.lineCode() + " 未缴余额不足，整笔零入账");
            }
        }
        if (!remittanceRepository.markStatementPaid(statement.id(), paidTotal, completed,
                completed ? LocalDateTime.now() : null, statement.version())) {
            throw versionConflict(request.expectedVersion(), statement.version());
        }
        return response;
    }

    /** 同流水同内容重放：返回首次冻结结果并标记重放。 */
    private RemittanceReceiptResponse replayReceipt(ReceiptRow committed) {
        try {
            return objectMapper.readValue(committed.responseBody(), RemittanceReceiptResponse.class)
                    .asReplayed();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("凭据响应反序列化失败", ex);
        }
    }

    /**
     * 回执指纹参数：线路分配按线路编码升序结构化，保证同内容不同顺序指纹一致。
     *
     * @param includeRequest 是否包含 requestId 与 expectedVersion（请求幂等指纹包含，
     *                       银行流水内容指纹不包含）
     */
    private List<Object> receiptFingerprintParts(RemittanceReceiptRequest request,
                                                 boolean includeRequest) {
        List<Object> parts = new ArrayList<>();
        if (includeRequest) {
            parts.add(request.requestId());
        }
        parts.add(request.statementNo());
        parts.add(request.batchNo());
        parts.add(request.bankFlowNo());
        parts.add(request.arrivedAt().toString());
        parts.add(request.totalCents());
        if (includeRequest) {
            parts.add(request.expectedVersion());
        }
        List<List<Object>> allocations = request.allocations().stream()
                .sorted(Comparator.comparing(RemittanceReceiptRequest.LineAllocation::lineCode))
                .map(a -> List.<Object>of(a.lineCode(), a.amountCents()))
                .toList();
        parts.add(allocations);
        return parts;
    }

    private static MetroException versionConflict(int expected, int actual) {
        return MetroException.conflict("VERSION_CONFLICT",
                "expectedVersion=" + expected + " 与当前版本 " + actual + " 不一致");
    }

    private <T extends ReplayableResponse<T>> T idempotent(String scope, String requestId,
                                                           String fingerprint, Class<T> type,
                                                           Supplier<T> action) {
        Optional<IdemRow> existing = metroRepository.findIdem(scope, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            return txTemplate.execute(status -> {
                // 事务开头占位：并发同键在此阻塞或冲突，随后走重放路径；失败回滚不占键
                metroRepository.insertIdemPlaceholder(scope, requestId, fingerprint);
                T response = action.get();
                metroRepository.updateIdemResponse(scope, requestId, toJson(response));
                return response;
            });
        } catch (DuplicateKeyException ex) {
            return metroRepository.findIdem(scope, requestId)
                    .map(row -> replay(row, fingerprint, type))
                    .orElseThrow(() -> ex);
        }
    }

    private <T extends ReplayableResponse<T>> T replay(IdemRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw MetroException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 但业务参数不一致，拒绝重复写操作");
        }
        try {
            return objectMapper.readValue(row.responseBody(), type).asReplayed();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    /**
     * 结构化指纹：将操作域与全部参数序列化为定长 JSON 数组后取 SHA-256。
     * JSON 转义保证含分隔符的合法文本也无法伪造字段边界，数值保持数值类型。
     */
    private String fingerprint(String scope, List<Object> parts) {
        List<Object> structured = new ArrayList<>();
        structured.add(scope);
        structured.addAll(parts);
        String json;
        try {
            json = objectMapper.writeValueAsString(structured);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("指纹序列化失败", ex);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
