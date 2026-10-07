package com.example.starter.metro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.metro.MetroRepository.IdemRow;
import com.example.starter.metro.RemittanceRepository.LineRow;
import com.example.starter.metro.RemittanceRepository.ReceiptDetailRow;
import com.example.starter.metro.RemittanceRepository.ReceiptRow;
import com.example.starter.metro.RemittanceRepository.StatementRow;
import com.example.starter.metro.RefundRepository.AdjustmentBatchRow;
import com.example.starter.metro.RefundRepository.BatchDetailRow;
import com.example.starter.metro.dto.RemittanceOpenRequest;
import com.example.starter.metro.dto.RemittanceOpenResponse;
import com.example.starter.metro.dto.RemittanceReceiptRequest;
import com.example.starter.metro.dto.RemittanceReceiptResponse;
import com.example.starter.metro.dto.RemittanceView;
import com.example.starter.metro.dto.ReplayableResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 缴回对账业务服务：对已封存非空调账批次建立独立缴回对账单，
 * 逐线路应缴取本批负向调整金额绝对值之和；合成银行回执可分次、乱序到账，
 * 全部线路恰缴齐才完成。原退款分摊、双人确认与封账金额不重算、不改写。
 *
 * <p>写操作双层幂等：requestId 用完整结构化指纹（成功同参重放、异参 409、
 * 失败不占键）；银行流水号全局唯一，同流水同完整内容返回首次结果并可绑定
 * 新的 requestId，换批/改时刻/改金额 409。余额、回执、版本与幂等同事务提交。
 */
@Service
public class RemittanceService {

    static final String SCOPE_OPEN = "REMITTANCE_OPEN";
    static final String SCOPE_RECEIPT = "REMITTANCE_RECEIPT";

    private final RemittanceRepository remittanceRepository;
    private final RefundRepository refundRepository;
    private final MetroRepository metroRepository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public RemittanceService(RemittanceRepository remittanceRepository,
                             RefundRepository refundRepository, MetroRepository metroRepository,
                             PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.remittanceRepository = remittanceRepository;
        this.refundRepository = refundRepository;
        this.metroRepository = metroRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 建立缴回对账单：仅非空已封存调账批次，每批最多一单，冻结全部来源明细。 */
    public RemittanceOpenResponse open(RemittanceOpenRequest request) {
        String fingerprint = fingerprint(SCOPE_OPEN, List.of(request.requestId(),
                request.statementNo(), request.batchNo(), request.createTime().toString()));
        return idempotent(SCOPE_OPEN, request.requestId(), fingerprint,
                RemittanceOpenResponse.class, () -> doOpen(request));
    }

    /** 提交合成银行回执：校验分配、原子入账，双层幂等与乐观版本。 */
    public RemittanceReceiptResponse submitReceipt(RemittanceReceiptRequest request) {
        List<Object> allocations = new ArrayList<>();
        for (RemittanceReceiptRequest.Allocation a : request.allocations()) {
            allocations.add(List.of(a.lineCode(), a.amountCents()));
        }
        // 请求指纹：覆盖 requestId 与全部参数（含 expectedVersion），用于 requestId 幂等
        String requestFingerprint = fingerprint(SCOPE_RECEIPT, List.of(request.requestId(),
                request.statementNo(), request.bankSerialNo(), request.arrivalTimeUtc().toString(),
                request.totalCents(), allocations, request.expectedVersion()));
        // 流水内容指纹：不含 requestId/版本，同流水同完整内容（批次/时刻/金额/分配）可绑定新 requestId
        String bankFingerprint = bankContentFingerprint(request.statementNo(),
                request.bankSerialNo(), request.arrivalTimeUtc().toString(), request.totalCents(),
                allocations);
        Optional<IdemRow> existing = metroRepository.findIdem(SCOPE_RECEIPT, request.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), requestFingerprint, RemittanceReceiptResponse.class);
        }
        try {
            return txTemplate.execute(status -> {
                // 事务开头占位：并发同 requestId 在此阻塞或冲突，随后走重放路径；失败回滚不占键
                metroRepository.insertIdemPlaceholder(SCOPE_RECEIPT, request.requestId(),
                        requestFingerprint);
                RemittanceReceiptResponse response = doSubmitReceipt(request, requestFingerprint,
                        bankFingerprint);
                metroRepository.updateIdemResponse(SCOPE_RECEIPT, request.requestId(),
                        toJson(response));
                return response;
            });
        } catch (DuplicateKeyException ex) {
            return metroRepository.findIdem(SCOPE_RECEIPT, request.requestId())
                    .map(row -> replay(row, requestFingerprint, RemittanceReceiptResponse.class))
                    .orElseGet(() -> replayBankSerialOrConflict(request, bankFingerprint, ex));
        }
    }

    /** 查询对账单：应缴、已缴、余额及流水分配链；应缴可从冻结来源明细复算。 */
    public RemittanceView view(String statementNo) {
        StatementRow statement = remittanceRepository.findStatementByNo(statementNo)
                .orElseThrow(() -> MetroException.notFound("REMITTANCE_NOT_FOUND",
                        "缴回对账单不存在: " + statementNo));
        List<LineRow> lines = remittanceRepository.findLines(statement.id());
        long totalDue = lines.stream().mapToLong(LineRow::dueCents).sum();
        long totalPaid = lines.stream().mapToLong(LineRow::paidCents).sum();
        // 从冻结来源明细复算应缴额，应与建单冻结的逐线路应缴一致
        long recomputedDue = remittanceRepository.findSourceDetails(statement.id()).stream()
                .mapToLong(d -> Math.abs(d.signedAmountCents())).sum();
        if (recomputedDue != totalDue || totalDue != statement.totalDueCents()) {
            throw new IllegalStateException("冻结明细复算应缴额与对账单不一致: " + statementNo);
        }
        List<RemittanceView.LineStatus> lineViews = lines.stream()
                .map(l -> new RemittanceView.LineStatus(l.lineCode(), l.dueCents(), l.paidCents(),
                        l.dueCents() - l.paidCents()))
                .toList();
        List<RemittanceView.ReceiptChain> receiptChains = new ArrayList<>();
        for (ReceiptRow receipt : remittanceRepository.findReceipts(statement.id())) {
            List<RemittanceView.ChainAllocation> chain = remittanceRepository
                    .findReceiptDetails(receipt.id()).stream()
                    .map(d -> new RemittanceView.ChainAllocation(d.lineCode(), d.amountCents()))
                    .toList();
            receiptChains.add(new RemittanceView.ReceiptChain(receipt.bankSerialNo(),
                    receipt.arrivalTimeUtc(), receipt.totalCents(), chain));
        }
        return new RemittanceView(statement.statementNo(),
                refundRepository.findAdjustmentBatchById(statement.batchId())
                        .map(AdjustmentBatchRow::batchNo)
                        .orElseThrow(() -> new IllegalStateException("来源调账批次缺失")),
                statement.status(), statement.version(), totalDue, totalPaid, totalDue - totalPaid,
                lineViews, receiptChains);
    }

    // ---- 建立对账单 ----

    private RemittanceOpenResponse doOpen(RemittanceOpenRequest request) {
        if (remittanceRepository.findStatementByNo(request.statementNo()).isPresent()) {
            throw MetroException.conflict("DUPLICATE_REMITTANCE_NO",
                    "缴回对账单号重复: " + request.statementNo());
        }
        AdjustmentBatchRow batch = refundRepository
                .findAdjustmentBatchByNoForUpdate(request.batchNo())
                .orElseThrow(() -> MetroException.notFound("ADJ_BATCH_NOT_FOUND",
                        "调账批次不存在: " + request.batchNo()));
        if (!"SEALED".equals(batch.status())) {
            throw MetroException.unprocessable("ADJ_BATCH_NOT_SEALED",
                    "仅已封存调账批次可建立缴回对账单: " + request.batchNo());
        }
        if (remittanceRepository.findStatementByBatchId(batch.id()).isPresent()) {
            throw MetroException.unprocessable("REMITTANCE_ALREADY_EXISTS",
                    "调账批次已建立缴回对账单，每批最多一单: " + request.batchNo());
        }
        List<BatchDetailRow> batchDetails = refundRepository.findBatchDetails(batch.id());
        // 逐线路应缴额取本批负向调整金额绝对值之和
        Map<String, Long> dueByLine = new LinkedHashMap<>();
        for (BatchDetailRow detail : batchDetails) {
            if (detail.amountCents() < 0) {
                dueByLine.merge(detail.lineCode(), -detail.amountCents(), Long::sum);
            }
        }
        if (dueByLine.isEmpty()) {
            throw MetroException.unprocessable("REMITTANCE_BATCH_EMPTY",
                    "已封存调账批次无负向调整明细，不能建立缴回对账单: " + request.batchNo());
        }
        long totalDue = dueByLine.values().stream().mapToLong(Long::longValue).sum();
        LocalDateTime now = LocalDateTime.now();
        long statementId = remittanceRepository.insertStatement(request.statementNo(), batch.id(),
                totalDue, request.requestId(), now);
        // 冻结全部来源明细：原调账快照不可覆盖，后续查询可从冻结明细复算
        for (BatchDetailRow detail : batchDetails) {
            if (detail.amountCents() < 0) {
                remittanceRepository.insertSourceDetail(statementId, batch.id(),
                        detail.adjustmentId(), detail.txnNo(), detail.lineCode(),
                        detail.amountCents(), -detail.amountCents(), now);
            }
        }
        List<RemittanceOpenResponse.LineDue> lineDues = new ArrayList<>();
        for (String line : dueByLine.keySet().stream().sorted().toList()) {
            remittanceRepository.insertLine(statementId, line, dueByLine.get(line), now);
            lineDues.add(new RemittanceOpenResponse.LineDue(line, dueByLine.get(line)));
        }
        return new RemittanceOpenResponse(request.statementNo(), request.batchNo(), "OPEN",
                totalDue, 1, lineDues, false);
    }

    // ---- 回执入账 ----

    private RemittanceReceiptResponse doSubmitReceipt(RemittanceReceiptRequest request,
                                                      String requestFingerprint,
                                                      String bankFingerprint) {
        StatementRow statement = remittanceRepository
                .findStatementByNoForUpdate(request.statementNo())
                .orElseThrow(() -> MetroException.notFound("REMITTANCE_NOT_FOUND",
                        "缴回对账单不存在: " + request.statementNo()));

        // 银行流水全局唯一：同流水先查（行已锁），同完整内容重放首次结果，换批/改参 409
        Optional<ReceiptRow> existingReceipt =
                remittanceRepository.findReceiptByBankSerialForUpdate(request.bankSerialNo());
        if (existingReceipt.isPresent()) {
            return replayExistingReceipt(statement, existingReceipt.get(), request,
                    bankFingerprint);
        }

        if ("COMPLETED".equals(statement.status())) {
            throw MetroException.unprocessable("REMITTANCE_COMPLETED",
                    "对账单全部线路已缴齐，拒绝新流水: " + request.statementNo());
        }
        if (request.expectedVersion() != statement.version()) {
            throw versionConflict(request.expectedVersion(), statement.version());
        }

        List<LineRow> lines = remittanceRepository.findLinesForUpdate(statement.id());
        Map<String, LineRow> lineMap = new LinkedHashMap<>();
        for (LineRow line : lines) {
            lineMap.put(line.lineCode(), line);
        }
        List<RemittanceReceiptRequest.Allocation> allocations = request.allocations();
        long allocSum = validateAllocations(request, lineMap, allocations);

        // 全部线路恰缴齐才完成：未出现在本笔分配中的线路必须已无余额
        Map<String, Long> newBalances = new LinkedHashMap<>();
        for (LineRow line : lines) {
            long allocated = allocations.stream()
                    .filter(a -> a.lineCode().equals(line.lineCode()))
                    .mapToLong(RemittanceReceiptRequest.Allocation::amountCents)
                    .findFirst().orElse(0L);
            newBalances.put(line.lineCode(), line.dueCents() - line.paidCents() - allocated);
        }
        boolean completes = newBalances.values().stream().allMatch(balance -> balance == 0L);

        // 所有线路同时更新：逐线路条件累加，任一失败整笔回滚（零入账）
        LocalDateTime now = LocalDateTime.now();
        for (RemittanceReceiptRequest.Allocation allocation : allocations) {
            int updated = remittanceRepository.payLine(statement.id(), allocation.lineCode(),
                    allocation.amountCents(), now);
            if (updated != 1) {
                throw MetroException.unprocessable("REMITTANCE_ALLOCATION_INVALID",
                        "线路 " + allocation.lineCode() + " 未缴余额不足，并发争抢被拒绝，整笔零入账");
            }
        }
        int statementUpdated = remittanceRepository.applyReceiptToStatement(statement.id(),
                request.totalCents(), request.expectedVersion(), completes, now);
        if (statementUpdated != 1) {
            // 版本/状态/总额条件兜底：并发下拒绝超缴
            throw versionConflict(request.expectedVersion(), statement.version());
        }

        String statusAfter = completes ? "COMPLETED" : "OPEN";
        List<RemittanceReceiptResponse.ReceiptAllocation> allocationViews = allocations.stream()
                .map(a -> new RemittanceReceiptResponse.ReceiptAllocation(a.lineCode(),
                        a.amountCents()))
                .toList();
        RemittanceReceiptResponse response = new RemittanceReceiptResponse(request.statementNo(),
                request.bankSerialNo(), request.arrivalTimeUtc(), allocSum, statusAfter,
                request.expectedVersion() + 1, allocationViews, false);

        int seqNo = remittanceRepository.nextReceiptSeqNo(statement.id());
        long receiptId;
        try {
            receiptId = remittanceRepository.insertReceipt(statement.id(),
                    request.bankSerialNo(), request.arrivalTimeUtc(), request.totalCents(),
                    request.requestId(), bankFingerprint, toJson(response), seqNo, now);
        } catch (DuplicateKeyException ex) {
            // 并发同流水号（可能指向不同批次）：全局唯一约束兜底，整笔回滚零入账
            throw MetroException.conflict("DUPLICATE_BANK_SERIAL",
                    "银行流水号全局唯一，并发重复提交被拒绝: " + request.bankSerialNo());
        }
        for (RemittanceReceiptRequest.Allocation allocation : allocations) {
            remittanceRepository.insertReceiptDetail(receiptId, statement.id(),
                    allocation.lineCode(), allocation.amountCents(), now);
        }
        return response;
    }

    /** 校验：线路互不相同且均属于本单、合计等于总额、每条不超过该线路未缴余额。 */
    private long validateAllocations(RemittanceReceiptRequest request, Map<String, LineRow> lineMap,
                                     List<RemittanceReceiptRequest.Allocation> allocations) {
        long allocSum = 0L;
        List<String> seen = new ArrayList<>();
        for (RemittanceReceiptRequest.Allocation allocation : allocations) {
            if (seen.contains(allocation.lineCode())) {
                throw MetroException.unprocessable("REMITTANCE_ALLOCATION_INVALID",
                        "同一回执内线路不得重复: " + allocation.lineCode());
            }
            seen.add(allocation.lineCode());
            LineRow line = lineMap.get(allocation.lineCode());
            if (line == null) {
                throw MetroException.unprocessable("REMITTANCE_ALLOCATION_INVALID",
                        "分配线路不属于本缴回对账单: " + allocation.lineCode());
            }
            long balance = line.dueCents() - line.paidCents();
            if (allocation.amountCents() > balance) {
                throw MetroException.unprocessable("REMITTANCE_ALLOCATION_INVALID",
                        "线路 " + allocation.lineCode() + " 分配 " + allocation.amountCents()
                                + " 分超过未缴余额 " + balance + " 分，整笔零入账");
            }
            allocSum += allocation.amountCents();
        }
        if (allocSum != request.totalCents()) {
            throw MetroException.unprocessable("REMITTANCE_ALLOCATION_INVALID",
                    "逐线路分配合计 " + allocSum + " 分不等于到账总额 " + request.totalCents()
                            + " 分，整笔零入账（差 " + (request.totalCents() - allocSum) + " 分）");
        }
        return allocSum;
    }

    /**
     * 同银行流水命中既有凭据：必须是同一对账单且完整内容一致，重放首次冻结结果
     * （允许绑定新的 requestId）；换批或时刻/金额/分配不一致返回 409。
     */
    private RemittanceReceiptResponse replayExistingReceipt(StatementRow statement,
                                                            ReceiptRow receipt,
                                                            RemittanceReceiptRequest request,
                                                            String bankFingerprint) {
        if (receipt.statementId() != statement.id()) {
            throw MetroException.conflict("DUPLICATE_BANK_SERIAL",
                    "银行流水号已用于其他缴回对账单，不同批次不得混用: " + request.bankSerialNo());
        }
        if (!receipt.fingerprint().equals(bankFingerprint)) {
            throw MetroException.conflict("BANK_SERIAL_CONFLICT",
                    "相同银行流水号但到账时刻/金额/分配不一致，拒绝重复入账: "
                            + request.bankSerialNo());
        }
        RemittanceReceiptResponse frozen = readReceiptResponse(receipt.responseBody());
        // 将首份结果绑定到本次（可能是新的）requestId 幂等记录
        metroRepository.updateIdemResponse(SCOPE_RECEIPT, request.requestId(),
                receipt.responseBody());
        return frozen.asReplayed();
    }

    /** 唯一约束冲突后：requestId 行不存在时，按银行流水号重放或判冲突。 */
    private RemittanceReceiptResponse replayBankSerialOrConflict(RemittanceReceiptRequest request,
                                                                 String bankFingerprint,
                                                                 DuplicateKeyException ex) {
        Optional<ReceiptRow> receipt =
                remittanceRepository.findReceiptByBankSerial(request.bankSerialNo());
        if (receipt.isPresent()) {
            if (!receipt.get().fingerprint().equals(bankFingerprint)) {
                throw MetroException.conflict("BANK_SERIAL_CONFLICT",
                        "相同银行流水号但内容不一致，拒绝重复入账: " + request.bankSerialNo());
            }
            return readReceiptResponse(receipt.get().responseBody()).asReplayed();
        }
        throw ex;
    }

    /**
     * 银行流水内容指纹：仅覆盖对账单、流水号、UTC 时刻、总额与逐线路分配，
     * 不含 requestId/expectedVersion；据此实现“同流水同完整内容可绑定新 requestId”。
     */
    private String bankContentFingerprint(String statementNo, String bankSerialNo,
                                          String arrivalTimeUtc, long totalCents,
                                          List<Object> allocations) {
        return fingerprint("REMITTANCE_BANK_RECEIPT",
                List.of(statementNo, bankSerialNo, arrivalTimeUtc, totalCents, allocations));
    }

    private RemittanceReceiptResponse readReceiptResponse(String body) {
        try {
            return objectMapper.readValue(body, RemittanceReceiptResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("银行流水响应反序列化失败", ex);
        }
    }

    private static MetroException versionConflict(int expected, int actual) {
        return MetroException.conflict("VERSION_CONFLICT",
                "expectedVersion=" + expected + " 与当前版本 " + actual + " 不一致");
    }

    // ---- 幂等与指纹（沿用结构化 JSON 指纹） ----

    private <T extends ReplayableResponse<T>> T idempotent(String scope, String requestId,
                                                           String fingerprint, Class<T> type,
                                                           Supplier<T> action) {
        Optional<IdemRow> existing = metroRepository.findIdem(scope, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            return txTemplate.execute(status -> {
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

    private <T extends ReplayableResponse<T>> T replay(IdemRow row, String fingerprint,
                                                       Class<T> type) {
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
            throw new IllegalStateException("幂等响应序列化失败", ex);
        }
    }

    /**
     * 结构化指纹：将操作域与全部参数（含分配数组）序列化为定长 JSON 后取 SHA-256，
     * JSON 转义保证含分隔符的合法文本无法伪造字段边界，数值保持数值类型。
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
