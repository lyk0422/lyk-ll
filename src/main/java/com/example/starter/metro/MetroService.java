package com.example.starter.metro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.metro.MetroRepository.BatchRow;
import com.example.starter.metro.MetroRepository.DetailRow;
import com.example.starter.metro.MetroRepository.DiscrepancyRow;
import com.example.starter.metro.MetroRepository.IdemRow;
import com.example.starter.metro.MetroRepository.TxnRow;
import com.example.starter.metro.dto.BatchViewResponse;
import com.example.starter.metro.dto.EntryRequest;
import com.example.starter.metro.dto.EntryResponse;
import com.example.starter.metro.dto.EstimateRequest;
import com.example.starter.metro.dto.EstimateResponse;
import com.example.starter.metro.dto.ExitRequest;
import com.example.starter.metro.dto.ExitResponse;
import com.example.starter.metro.dto.ReplayableResponse;
import com.example.starter.metro.dto.SealRequest;
import com.example.starter.metro.dto.SealResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 轨交清分业务服务：进闸、出闸计费、暂记与补登、跨线分摊、日结封账。
 * 交易与批次均以 requestId 幂等：指纹覆盖 requestId 与全部业务字段，
 * 同键同参返回首次结果，同键异参返回 409 且不重复入账。
 */
@Service
public class MetroService {

    /** 换乘优惠窗口：出站至换乘进站 30 分钟内（含恰 30 分钟）。 */
    static final Duration TRANSFER_WINDOW = Duration.ofMinutes(30);
    /** 超时乘车阈值：进闸至出闸超过 4 小时（恰 4 小时不罚）。 */
    static final Duration OVERTIME_LIMIT = Duration.ofHours(4);
    /** 暂记分摊的占位线路编码（无法确定承运路径）。 */
    static final String UNALLOCATED_LINE = "UNALLOC";

    private final MetroRepository repository;
    private final MileageGraph mileageGraph;
    private final FareCalculator fareCalculator;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    private volatile Long maxNetworkFareCents;

    public MetroService(MetroRepository repository, MileageGraph mileageGraph,
                        FareCalculator fareCalculator, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.mileageGraph = mileageGraph;
        this.fareCalculator = fareCalculator;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 进闸。 */
    public EntryResponse entry(EntryRequest request) {
        String fingerprint = fingerprint("ENTRY", request.requestId(), request.txnNo(),
                request.cardId(), request.station(), request.entryTime().toString());
        return idempotent("ENTRY", request.requestId(), fingerprint, EntryResponse.class,
                () -> doEntry(request, fingerprint));
    }

    /** 出闸；对已暂记行程执行补登（同事务冲销暂记并按实结算）。 */
    public ExitResponse exit(ExitRequest request) {
        String fingerprint = fingerprint("EXIT", request.requestId(), request.txnNo(),
                request.station(), request.exitTime().toString());
        return idempotent("EXIT", request.requestId(), fingerprint, ExitResponse.class,
                () -> doExit(request));
    }

    /** 出闸缺失行程按最远站票价暂记。 */
    public EstimateResponse estimate(EstimateRequest request) {
        String fingerprint = fingerprint("ESTIMATE", request.requestId(), request.txnNo());
        return idempotent("ESTIMATE", request.requestId(), fingerprint, EstimateResponse.class,
                () -> doEstimate(request));
    }

    /** 日结封账（终态，重复封账幂等成功）。 */
    public SealResponse seal(SealRequest request) {
        String fingerprint = fingerprint("SEAL", request.requestId(), request.bizDate().toString());
        return idempotent("SEAL", request.requestId(), fingerprint, SealResponse.class,
                () -> doSeal(request));
    }

    /** 查询批次及明细、差异（逐笔复算）。 */
    public BatchViewResponse batchView(LocalDate bizDate) {
        BatchRow batch = repository.findBatchByDate(bizDate)
                .orElseThrow(() -> MetroException.notFound("BATCH_NOT_FOUND", "批次不存在: " + bizDate));
        List<DetailRow> details = repository.findDetailsByBatch(batch.id());
        boolean sealed = "SEALED".equals(batch.status());
        long total = sealed ? batch.totalCents()
                : details.stream().mapToLong(DetailRow::amountCents).sum();
        int count = sealed ? batch.txnCount() : repository.countTxnsInBatch(batch.id());
        List<BatchViewResponse.DetailView> detailViews = new ArrayList<>();
        for (DetailRow d : details) {
            detailViews.add(new BatchViewResponse.DetailView(d.txnNo(), d.lineCode(), d.distanceM(),
                    d.ratio(), d.amountCents(), d.entryType()));
        }
        List<BatchViewResponse.DiscrepancyView> discrepancyViews = new ArrayList<>();
        for (DiscrepancyRow d : repository.findDiscrepanciesByDate(bizDate)) {
            discrepancyViews.add(new BatchViewResponse.DiscrepancyView(d.txnNo(), d.amountCents(), d.reason()));
        }
        return new BatchViewResponse(batch.bizDate().toString(), batch.status(), total, count,
                detailViews, discrepancyViews);
    }

    private EntryResponse doEntry(EntryRequest request, String fingerprint) {
        if (!mileageGraph.stationExists(request.station())) {
            throw MetroException.badRequest("STATION_UNKNOWN", "未知车站编码: " + request.station());
        }
        TxnRow row = new TxnRow(null, request.txnNo(), request.cardId(), request.station(),
                request.entryTime(), null, null, "ENTERED", null, 0L, null, 0L, false, false,
                null, null, request.requestId(), fingerprint);
        repository.insertTxn(row);
        return new EntryResponse(request.txnNo(), "ENTERED", false);
    }

    private ExitResponse doExit(ExitRequest request) {
        TxnRow txn = repository.findTxnByNoForUpdate(request.txnNo())
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND", "交易流水不存在: " + request.txnNo()));
        if ("COMPLETED".equals(txn.status())) {
            throw MetroException.conflict("TXN_ALREADY_COMPLETED", "交易已完成结算: " + request.txnNo());
        }
        if (request.exitTime().isBefore(txn.entryTime())) {
            throw MetroException.badRequest("EXIT_BEFORE_ENTRY", "出闸时间早于进闸时间");
        }
        if (!mileageGraph.stationExists(request.station())) {
            throw MetroException.badRequest("STATION_UNKNOWN", "未知车站编码: " + request.station());
        }
        boolean compensated = "ESTIMATED".equals(txn.status());
        boolean lateDiscrepancy = false;

        if (compensated) {
            lateDiscrepancy = reverseEstimates(txn);
        }

        MileageGraph.ShortestPath path = mileageGraph.shortestPath(txn.entryStation(), request.station());
        long distanceM = path.totalDistanceM();

        long chainBaseM = 0L;
        boolean transfer = false;
        Optional<TxnRow> prev = repository.findLatestExitedTxnForCard(txn.cardId(), txn.entryTime());
        if (prev.isPresent()) {
            Duration gap = Duration.between(prev.get().exitTime(), txn.entryTime());
            if (!gap.isNegative() && gap.compareTo(TRANSFER_WINDOW) <= 0) {
                transfer = true;
                chainBaseM = prev.get().chainBaseM() + prev.get().distanceM();
            }
        }

        long normalFare = fareCalculator.fareCents(chainBaseM + distanceM)
                - fareCalculator.fareCents(chainBaseM);
        long penaltyCents = 0L;
        if (Duration.between(txn.entryTime(), request.exitTime()).compareTo(OVERTIME_LIMIT) > 0) {
            penaltyCents = Math.max(0L, maxNetworkFareCents() - normalFare);
        }
        long fareCents = normalFare + penaltyCents;

        LocalDate bizDate = request.exitTime().toLocalDate();
        BatchRow batch = getOrCreateBatchForUpdate(bizDate);
        Long batchId = batch.id();
        if ("SEALED".equals(batch.status())) {
            repository.insertDiscrepancy(new DiscrepancyRow(null, bizDate, txn.txnNo(), fareCents,
                    "LATE_AFTER_SEAL"));
            lateDiscrepancy = true;
            batchId = null;
        } else {
            allocate(batch.id(), txn.txnNo(), path.lineDistanceM(), distanceM, fareCents,
                    compensated ? "SETTLE" : "NORMAL");
        }

        TxnRow updated = new TxnRow(txn.id(), txn.txnNo(), txn.cardId(), txn.entryStation(),
                txn.entryTime(), request.station(), request.exitTime(), "COMPLETED", distanceM,
                chainBaseM, fareCents, penaltyCents, transfer, false, batchId, bizDate,
                txn.requestId(), txn.fingerprint());
        repository.updateTxnSettlement(updated);
        return new ExitResponse(txn.txnNo(), "COMPLETED", distanceM, fareCents, penaltyCents,
                transfer, compensated, bizDate.toString(), lateDiscrepancy, false);
    }

    /**
     * 冲销暂记明细：原批次未封账则在原批次内红字冲销；已封账则登记差异。
     *
     * @return 是否产生封账后差异登记
     */
    private boolean reverseEstimates(TxnRow txn) {
        boolean late = false;
        for (DetailRow estimate : repository.findDetailsByTxnAndType(txn.txnNo(), "ESTIMATE")) {
            BatchRow estimateBatch = repository.findBatchById(estimate.batchId())
                    .orElseThrow(() -> new IllegalStateException("暂记批次缺失: " + estimate.batchId()));
            if ("OPEN".equals(estimateBatch.status())) {
                repository.insertDetail(new DetailRow(null, estimate.batchId(), txn.txnNo(),
                        estimate.lineCode(), estimate.distanceM(), estimate.ratio(),
                        -estimate.amountCents(), "REVERSAL"));
            } else {
                repository.insertDiscrepancy(new DiscrepancyRow(null, estimateBatch.bizDate(),
                        txn.txnNo(), -estimate.amountCents(), "REVERSAL_AFTER_SEAL"));
                late = true;
            }
        }
        return late;
    }

    private EstimateResponse doEstimate(EstimateRequest request) {
        TxnRow txn = repository.findTxnByNoForUpdate(request.txnNo())
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND", "交易流水不存在: " + request.txnNo()));
        if (!"ENTERED".equals(txn.status())) {
            throw MetroException.conflict("TXN_STATE_CONFLICT",
                    "仅未出闸行程可暂记，当前状态: " + txn.status());
        }
        long estimateCents = fareCalculator.fareCents(mileageGraph.farthestDistanceM(txn.entryStation()));
        LocalDate bizDate = txn.entryTime().toLocalDate();
        BatchRow batch = getOrCreateBatchForUpdate(bizDate);
        Long batchId = batch.id();
        boolean lateDiscrepancy = false;
        if ("SEALED".equals(batch.status())) {
            repository.insertDiscrepancy(new DiscrepancyRow(null, bizDate, txn.txnNo(), estimateCents,
                    "ESTIMATE_AFTER_SEAL"));
            lateDiscrepancy = true;
            batchId = null;
        } else {
            repository.insertDetail(new DetailRow(null, batch.id(), txn.txnNo(), UNALLOCATED_LINE,
                    0L, "1.0000", estimateCents, "ESTIMATE"));
        }
        TxnRow updated = new TxnRow(txn.id(), txn.txnNo(), txn.cardId(), txn.entryStation(),
                txn.entryTime(), null, null, "ESTIMATED", null, 0L, estimateCents, 0L, false, true,
                batchId, bizDate, txn.requestId(), txn.fingerprint());
        repository.updateTxnSettlement(updated);
        return new EstimateResponse(txn.txnNo(), "ESTIMATED", estimateCents, bizDate.toString(),
                lateDiscrepancy, false);
    }

    private SealResponse doSeal(SealRequest request) {
        BatchRow batch = repository.findBatchByDateForUpdate(request.bizDate())
                .orElseThrow(() -> MetroException.notFound("BATCH_NOT_FOUND",
                        "批次不存在: " + request.bizDate()));
        if ("SEALED".equals(batch.status())) {
            // 封账为终态：重复封账返回幂等成功，不改已封账合计
            return new SealResponse(batch.bizDate().toString(), "SEALED", batch.totalCents(),
                    batch.txnCount(), true);
        }
        long totalCents = repository.findDetailsByBatch(batch.id()).stream()
                .mapToLong(DetailRow::amountCents).sum();
        int txnCount = repository.countTxnsInBatch(batch.id());
        repository.sealBatch(batch.id(), totalCents, txnCount, request.requestId(), LocalDateTime.now());
        return new SealResponse(batch.bizDate().toString(), "SEALED", totalCents, txnCount, false);
    }

    /**
     * 跨线分摊：按各线路承运里程比例（四位小数）分摊，金额取整到分，
     * 合计必须等于实收金额，差额归入里程最长线路。
     */
    private void allocate(long batchId, String txnNo, Map<String, Long> lineDistanceM,
                          long totalDistanceM, long fareCents, String entryType) {
        if (lineDistanceM.isEmpty()) {
            repository.insertDetail(new DetailRow(null, batchId, txnNo, UNALLOCATED_LINE, 0L,
                    "1.0000", fareCents, entryType));
            return;
        }
        String longestLine = lineDistanceM.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .orElseThrow()
                .getKey();
        long assigned = 0L;
        List<String> lines = lineDistanceM.keySet().stream().sorted().toList();
        for (String line : lines) {
            long lineDistance = lineDistanceM.get(line);
            java.math.BigDecimal ratio = java.math.BigDecimal.valueOf(lineDistance)
                    .divide(java.math.BigDecimal.valueOf(totalDistanceM), 4,
                            java.math.RoundingMode.HALF_UP);
            long amountCents;
            if (line.equals(longestLine)) {
                continue;
            }
            amountCents = java.math.BigDecimal.valueOf(fareCents).multiply(ratio)
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValue();
            assigned += amountCents;
            repository.insertDetail(new DetailRow(null, batchId, txnNo, line, lineDistance,
                    ratio.toPlainString(), amountCents, entryType));
        }
        java.math.BigDecimal longestRatio = java.math.BigDecimal.valueOf(lineDistanceM.get(longestLine))
                .divide(java.math.BigDecimal.valueOf(totalDistanceM), 4,
                        java.math.RoundingMode.HALF_UP);
        repository.insertDetail(new DetailRow(null, batchId, txnNo, longestLine,
                lineDistanceM.get(longestLine), longestRatio.toPlainString(),
                fareCents - assigned, entryType));
    }

    private BatchRow getOrCreateBatchForUpdate(LocalDate bizDate) {
        Optional<BatchRow> existing = repository.findBatchByDateForUpdate(bizDate);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            repository.insertBatch(bizDate);
        } catch (DuplicateKeyException ex) {
            // 并发建批：重新加锁读取
        }
        return repository.findBatchByDateForUpdate(bizDate)
                .orElseThrow(() -> new IllegalStateException("批次创建失败: " + bizDate));
    }

    private long maxNetworkFareCents() {
        Long cached = maxNetworkFareCents;
        if (cached == null) {
            synchronized (this) {
                if (maxNetworkFareCents == null) {
                    maxNetworkFareCents = fareCalculator.fareCents(mileageGraph.maxNetworkDistanceM());
                }
                cached = maxNetworkFareCents;
            }
        }
        return cached;
    }

    private <T extends ReplayableResponse<T>> T idempotent(String scope, String requestId,
                                                           String fingerprint, Class<T> type,
                                                           Supplier<T> action) {
        Optional<IdemRow> existing = repository.findIdem(scope, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            return txTemplate.execute(status -> {
                // 事务开头占位：并发同键在此阻塞或冲突，随后走重放路径
                repository.insertIdemPlaceholder(scope, requestId, fingerprint);
                T response = action.get();
                repository.updateIdemResponse(scope, requestId, toJson(response));
                return response;
            });
        } catch (DuplicateKeyException ex) {
            // 并发同键：以首次提交为准；否则为流水号唯一约束冲突
            return repository.findIdem(scope, requestId)
                    .map(row -> replay(row, fingerprint, type))
                    .orElseThrow(() -> MetroException.conflict("DUPLICATE_TXN_NO", "交易流水号重复"));
        }
    }

    private <T extends ReplayableResponse<T>> T replay(IdemRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw MetroException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 但业务参数不一致，拒绝重复入账");
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

    private String fingerprint(String scope, String... parts) {
        StringBuilder joined = new StringBuilder(scope);
        for (String part : parts) {
            joined.append('|').append(part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
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
