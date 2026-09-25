package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.JudgmentCategory;
import com.example.starter.error.ApiException;
import com.example.starter.repo.BatchRepository;
import com.example.starter.repo.BatchRepository.BatchRow;
import com.example.starter.repo.BatchRepository.HeadRow;
import com.example.starter.repo.BatchRepository.IdempotencyRow;
import com.example.starter.repo.BatchRepository.JudgmentRow;
import com.example.starter.repo.BatchRepository.ReceiptRow;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.DeclareRequest;
import com.example.starter.web.dto.DeclareResponse;
import com.example.starter.web.dto.InspectionRequest;
import com.example.starter.web.dto.InspectionResponse;
import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.JudgmentResponse;
import com.example.starter.web.dto.PreQuarantineRequest;
import com.example.starter.web.dto.PreQuarantineResponse;
import com.example.starter.web.dto.ReceiptRequest;
import com.example.starter.web.dto.ReceiptResponse;
import com.example.starter.web.dto.ReconciliationEntry;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 屠宰检疫业务服务：申报→宰前检疫→同步检验→判定分流→出厂核销。
 * 申报、检验与回执登记均以 requestId 幂等；判定分流与库存标识在同一事务内完成。
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    /** 宰前检疫有效期：自申报起 6 小时（UTC），恰满 6 小时即失效须重新申报。 */
    static final Duration QUARANTINE_VALIDITY = Duration.ofHours(6);

    private final BatchRepository repo;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    /** 漏检暂停独立事务：判定请求整体回滚时，暂停状态仍须落库。 */
    private final TransactionTemplate pauseTemplate;

    public BatchService(BatchRepository repo, Clock clock, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.pauseTemplate = new TransactionTemplate(transactionManager);
        this.pauseTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------- 宰前申报 ----------

    /**
     * 宰前申报：新批次建档并建立头只台账；批次已存在且 6 小时有效期恰满/已过且未进入后续环节时，
     * 视为重新申报，重置申报时间与头只台账；其余情况拒绝。
     */
    @Transactional
    public DeclareResponse declare(DeclareRequest req) {
        return idempotent("declare", req.requestId(), req, DeclareResponse.class, () -> doDeclare(req));
    }

    private DeclareResponse doDeclare(DeclareRequest req) {
        validateDistinctHeadNos(req.headNos());
        String now = now();
        Optional<BatchRow> existing = repo.findBatchByNo(req.batchNo());
        if (existing.isEmpty()) {
            repo.insertBatch(req.batchNo(), req.species(), req.farmOrigin(), req.headNos().size(),
                    BatchStatus.DECLARED.name(), now, now);
            BatchRow batch = mustFind(req.batchNo());
            insertHeads(batch.id(), req.headNos(), now);
            return new DeclareResponse(batch.batchNo(), batch.status(), now, req.headNos().size());
        }
        BatchRow batch = existing.get();
        if (!BatchStatus.DECLARED.name().equals(batch.status())) {
            throw ApiException.conflict("批次已进入后续环节，不允许重新申报");
        }
        Instant declaredAt = Instant.parse(batch.declaredAt());
        if (Instant.now(clock).isBefore(declaredAt.plus(QUARANTINE_VALIDITY))) {
            throw ApiException.conflict("批次已申报且仍在宰前检疫 6 小时有效期内");
        }
        repo.resetForRedeclare(batch.id(), req.species(), req.farmOrigin(), req.headNos().size(), now);
        repo.deleteHeads(batch.id());
        insertHeads(batch.id(), req.headNos(), now);
        return new DeclareResponse(batch.batchNo(), BatchStatus.DECLARED.name(), now, req.headNos().size());
    }

    // ---------- 宰前检疫 ----------

    /**
     * 宰前检疫：自申报起 6 小时内（UTC）有效，恰满 6 小时未屠宰须重新申报。
     * 同一批次同时刻只允许一条检验链（DECLARED→PRE_QUARANTINED 条件流转保证）。
     */
    @Transactional
    public PreQuarantineResponse preQuarantine(String batchNo, PreQuarantineRequest req) {
        return idempotent("pre-quarantine", req.requestId(), req, PreQuarantineResponse.class,
                () -> doPreQuarantine(batchNo));
    }

    private PreQuarantineResponse doPreQuarantine(String batchNo) {
        BatchRow batch = mustFind(batchNo);
        if (!BatchStatus.DECLARED.name().equals(batch.status())) {
            throw ApiException.conflict("批次当前状态不允许宰前检疫");
        }
        Instant nowInstant = Instant.now(clock);
        Instant deadline = Instant.parse(batch.declaredAt()).plus(QUARANTINE_VALIDITY);
        if (!nowInstant.isBefore(deadline)) {
            throw ApiException.unprocessable("宰前检疫已超过申报后 6 小时有效期，须重新申报");
        }
        String now = nowInstant.toString();
        int updated = repo.markPreQuarantined(batch.id(), now);
        if (updated == 0) {
            throw ApiException.conflict("并发检疫冲突：同一批次同时刻只允许一条检验链");
        }
        return new PreQuarantineResponse(batchNo, BatchStatus.PRE_QUARANTINED.name(), now);
    }

    // ---------- 同步检验 ----------

    /**
     * 同步检验：按头只编号一一对应登记；漏检头只将在判定分流时导致整批暂停流转。
     */
    @Transactional
    public InspectionResponse inspect(String batchNo, InspectionRequest req) {
        return idempotent("inspection", req.requestId(), req, InspectionResponse.class,
                () -> doInspect(batchNo, req));
    }

    private InspectionResponse doInspect(String batchNo, InspectionRequest req) {
        BatchRow batch = mustFind(batchNo);
        String status = batch.status();
        boolean inspectable = BatchStatus.PRE_QUARANTINED.name().equals(status)
                || BatchStatus.INSPECTING.name().equals(status)
                || BatchStatus.PAUSED.name().equals(status);
        if (!inspectable) {
            throw ApiException.conflict("批次当前状态不允许同步检验");
        }
        repo.findHead(batch.id(), req.headNo())
                .orElseThrow(() -> ApiException.notFound("头只编号不存在：" + req.headNo()));
        int updated = repo.markHeadInspected(batch.id(), req.headNo(), req.result(), now());
        if (updated == 0) {
            throw ApiException.conflict("头只已完成同步检验，重复检验须使用原 requestId 重放");
        }
        if (BatchStatus.PRE_QUARANTINED.name().equals(status)) {
            repo.transitionStatus(batch.id(), List.of(BatchStatus.PRE_QUARANTINED.name()),
                    BatchStatus.INSPECTING.name());
        }
        int inspected = repo.countInspectedHeads(batch.id());
        return new InspectionResponse(batchNo, req.headNo(), "INSPECTED", inspected, batch.headCount());
    }

    // ---------- 判定分流 ----------

    /**
     * 判定分流：合格/有条件食用/化制/销毁四类；判定与库存标识在同一事务内完成。
     * 存在漏检头只时整批暂停流转并拒绝判定。
     */
    @Transactional
    public JudgmentResponse judge(String batchNo, JudgmentRequest req) {
        return idempotent("judgment", req.requestId(), req, JudgmentResponse.class,
                () -> doJudge(batchNo, req));
    }

    private JudgmentResponse doJudge(String batchNo, JudgmentRequest req) {
        BatchRow batch = mustFind(batchNo);
        String status = batch.status();
        boolean judgeable = BatchStatus.PRE_QUARANTINED.name().equals(status)
                || BatchStatus.INSPECTING.name().equals(status)
                || BatchStatus.PAUSED.name().equals(status);
        if (!judgeable) {
            throw ApiException.conflict("批次当前状态不允许判定分流");
        }
        List<HeadRow> heads = repo.findHeads(batch.id());
        List<String> missing = heads.stream()
                .filter(h -> "PENDING".equals(h.status()))
                .map(HeadRow::headNo)
                .toList();
        if (!missing.isEmpty()) {
            // 独立事务落库暂停状态：判定请求本身回滚，整批暂停仍然生效
            pauseTemplate.executeWithoutResult(s -> repo.transitionStatus(batch.id(),
                    List.of(BatchStatus.PRE_QUARANTINED.name(), BatchStatus.INSPECTING.name(),
                            BatchStatus.PAUSED.name()),
                    BatchStatus.PAUSED.name()));
            throw ApiException.conflict("存在漏检头只 " + missing + "，整批暂停流转");
        }
        Map<String, HeadRow> byHeadNo = new LinkedHashMap<>();
        for (HeadRow head : heads) {
            byHeadNo.put(head.headNo(), head);
        }
        Set<String> covered = new HashSet<>();
        List<ResolvedJudgment> resolved = new ArrayList<>();
        for (JudgmentRequest.JudgmentItem item : req.judgments()) {
            if (!byHeadNo.containsKey(item.headNo())) {
                throw ApiException.unprocessable("判定包含批次外头只：" + item.headNo());
            }
            if (!covered.add(item.headNo())) {
                throw ApiException.unprocessable("判定包含重复头只：" + item.headNo());
            }
            resolved.add(resolve(item));
        }
        if (covered.size() != heads.size()) {
            throw ApiException.unprocessable("判定须覆盖批次全部头只，缺 "
                    + (heads.size() - covered.size()) + " 头");
        }
        String now = now();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ResolvedJudgment r : resolved) {
            boolean qualified = r.category() == JudgmentCategory.QUALIFIED;
            boolean conditional = r.category() == JudgmentCategory.CONDITIONAL;
            repo.insertJudgment(batch.id(), r.headNo(), r.category().name(),
                    conditional && Boolean.TRUE.equals(r.highTemp()),
                    conditional ? r.circulationArea() : null, now);
            repo.applyJudgment(batch.id(), r.headNo(), r.category().name(),
                    conditional ? r.highTemp() : null,
                    conditional ? r.circulationArea() : null,
                    qualified, now);
            counts.merge(r.category().name(), 1, Integer::sum);
        }
        repo.markJudged(batch.id(), now);
        return new JudgmentResponse(batchNo, BatchStatus.JUDGED.name(), resolved.size(), counts);
    }

    /** 单头只判定项解析结果。 */
    private record ResolvedJudgment(String headNo, JudgmentCategory category, Boolean highTemp,
                                    String circulationArea) {
    }

    private ResolvedJudgment resolve(JudgmentRequest.JudgmentItem item) {
        JudgmentCategory category = parseCategory(item.category());
        if (category == JudgmentCategory.CONDITIONAL
                && (!Boolean.TRUE.equals(item.highTemp())
                || item.circulationArea() == null || item.circulationArea().isBlank())) {
            throw ApiException.unprocessable("有条件食用须标注高温处理并限定流通区域");
        }
        return new ResolvedJudgment(item.headNo(), category, item.highTemp(), item.circulationArea());
    }

    // ---------- 无害化处理回执 ----------

    /**
     * 回执登记：化制/销毁须取得无害化处理回执闭环；批次已核销时回执按迟到处理，
     * 只记迟到事件并告警，不改变批次状态。
     */
    @Transactional
    public ReceiptResponse registerReceipt(String batchNo, ReceiptRequest req) {
        return idempotent("receipt", req.requestId(), req, ReceiptResponse.class,
                () -> doRegisterReceipt(batchNo, req));
    }

    private ReceiptResponse doRegisterReceipt(String batchNo, ReceiptRequest req) {
        BatchRow batch = mustFind(batchNo);
        JudgmentCategory category = parseCategory(req.category());
        if (category != JudgmentCategory.RENDERING && category != JudgmentCategory.DESTRUCTION) {
            throw ApiException.unprocessable("无害化处理回执仅支持化制（RENDERING）与销毁（DESTRUCTION）类别");
        }
        String now = now();
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            repo.insertReceipt(batch.id(), req.receiptNo(), category.name(), req.headCount(),
                    req.processor(), true, now);
            repo.insertLateEvent(batch.id(), "LATE_RECEIPT",
                    "核销后迟到回执 " + req.receiptNo() + "，类别 " + category.name()
                            + "，头只数 " + req.headCount(),
                    now);
            log.warn("批次 {} 已出厂核销，回执 {} 迟到登记并告警", batchNo, req.receiptNo());
            return new ReceiptResponse(batchNo, req.receiptNo(), category.name(), req.headCount(),
                    true, reconciliation(batch.id()));
        }
        if (!BatchStatus.JUDGED.name().equals(batch.status())) {
            throw ApiException.conflict("批次未完成判定分流，不允许登记无害化处理回执");
        }
        repo.insertReceipt(batch.id(), req.receiptNo(), category.name(), req.headCount(),
                req.processor(), false, now);
        return new ReceiptResponse(batchNo, req.receiptNo(), category.name(), req.headCount(),
                false, reconciliation(batch.id()));
    }

    // ---------- 出厂核销 ----------

    /**
     * 出厂核销：回执头只数与判定数恰等受理、不等拒绝；核销为终态，重复核销返回幂等成功。
     */
    @Transactional
    public ReleaseResponse release(String batchNo, ReleaseRequest req) {
        return idempotent("release", req.requestId(), req, ReleaseResponse.class,
                () -> doRelease(batchNo));
    }

    private ReleaseResponse doRelease(String batchNo) {
        BatchRow batch = mustFind(batchNo);
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            return new ReleaseResponse(batchNo, BatchStatus.RELEASED.name(), batch.releasedAt(),
                    reconciliation(batch.id()));
        }
        if (!BatchStatus.JUDGED.name().equals(batch.status())) {
            throw ApiException.conflict("批次未达到可核销状态");
        }
        List<ReconciliationEntry> reconciliation = reconciliation(batch.id());
        List<String> mismatched = reconciliation.stream()
                .filter(e -> !e.matched())
                .map(e -> e.category() + "(判定 " + e.judgedCount() + " / 回执 " + e.receivedCount() + ")")
                .toList();
        if (!mismatched.isEmpty()) {
            throw ApiException.conflict("回执头只数与判定数不一致，拒绝出厂：" + mismatched);
        }
        String now = now();
        int updated = repo.markReleased(batch.id(), now);
        if (updated == 0) {
            throw ApiException.conflict("并发核销冲突");
        }
        return new ReleaseResponse(batchNo, BatchStatus.RELEASED.name(), now, reconciliation);
    }

    // ---------- 批次视图 ----------

    /**
     * 批次全量视图：头只台账、判定明细与回执勾稽均可由明细复算。
     */
    @Transactional(readOnly = true)
    public BatchView getView(String batchNo) {
        BatchRow batch = mustFind(batchNo);
        List<BatchView.HeadView> heads = repo.findHeads(batch.id()).stream()
                .map(h -> new BatchView.HeadView(h.headNo(), h.status(), h.inspectionResult(),
                        h.judgmentCategory(), h.highTemp(), h.circulationArea(), h.inventoryMarked()))
                .toList();
        List<BatchView.JudgmentView> judgments = repo.findJudgments(batch.id()).stream()
                .map(j -> new BatchView.JudgmentView(j.headNo(), j.category(), j.highTemp(),
                        j.circulationArea()))
                .toList();
        List<BatchView.ReceiptView> receipts = repo.findReceipts(batch.id()).stream()
                .map(r -> new BatchView.ReceiptView(r.receiptNo(), r.category(), r.headCount(),
                        r.processor(), r.late(), r.receivedAt()))
                .toList();
        List<BatchView.LateEventView> lateEvents = repo.findLateEvents(batch.id()).stream()
                .map(e -> new BatchView.LateEventView(e.eventType(), e.detail(), e.createdAt()))
                .toList();
        return new BatchView(batch.batchNo(), batch.status(), batch.species(), batch.farmOrigin(),
                batch.declaredAt(), batch.preQuarantinedAt(), batch.judgedAt(), batch.releasedAt(),
                heads, judgments, receipts, lateEvents, reconciliation(batch.id()));
    }

    // ---------- 内部：勾稽 / 幂等 / 工具 ----------

    /** 回执勾稽：按化制/销毁类别对比判定头只数与已登记回执头只数（不含迟到回执），恰等一致。 */
    private List<ReconciliationEntry> reconciliation(long batchId) {
        List<JudgmentRow> judgments = repo.findJudgments(batchId);
        List<ReceiptRow> receipts = repo.findReceipts(batchId);
        List<ReconciliationEntry> entries = new ArrayList<>();
        for (JudgmentCategory category : List.of(JudgmentCategory.RENDERING, JudgmentCategory.DESTRUCTION)) {
            int judged = (int) judgments.stream()
                    .filter(j -> category.name().equals(j.category()))
                    .count();
            int received = receipts.stream()
                    .filter(r -> category.name().equals(r.category()) && !r.late())
                    .mapToInt(ReceiptRow::headCount)
                    .sum();
            entries.add(new ReconciliationEntry(category.name(), judged, received, judged == received));
        }
        return entries;
    }

    /**
     * 幂等受理：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参返回 409 且不改变批次状态。
     * 并发下同键请求依赖 requestId 全局唯一约束，仅首次受理成功，其余重放首次结果。
     */
    private <T> T idempotent(String endpoint, String requestId, Object request, Class<T> type,
                             Supplier<T> action) {
        String fingerprint = fingerprint(requestId, request);
        Optional<IdempotencyRow> existing = repo.findIdempotency(requestId);
        if (existing.isPresent()) {
            IdempotencyRow record = existing.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("相同 requestId 携带不同业务参数，拒绝受理");
            }
            return readValue(record.responseBody(), type);
        }
        try {
            T result = action.get();
            repo.insertIdempotency(requestId, endpoint, fingerprint, writeValue(result), now());
            return result;
        } catch (DuplicateKeyException e) {
            Optional<IdempotencyRow> concurrent = repo.findIdempotency(requestId);
            if (concurrent.isPresent() && concurrent.get().fingerprint().equals(fingerprint)) {
                return readValue(concurrent.get().responseBody(), type);
            }
            throw ApiException.conflict("并发受理冲突：批次或请求标识已被其他请求占用");
        }
    }

    private String fingerprint(String requestId, Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (requestId + "\n" + json).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("请求指纹计算失败", e);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private BatchRow mustFind(String batchNo) {
        return repo.findBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + batchNo));
    }

    private JudgmentCategory parseCategory(String category) {
        try {
            return JudgmentCategory.valueOf(category);
        } catch (Exception e) {
            throw ApiException.unprocessable("未知判定类别：" + category
                    + "，支持 QUALIFIED/CONDITIONAL/RENDERING/DESTRUCTION");
        }
    }

    private void validateDistinctHeadNos(List<String> headNos) {
        Set<String> seen = new HashSet<>();
        for (String headNo : headNos) {
            if (!seen.add(headNo)) {
                throw ApiException.unprocessable("头只编号重复：" + headNo);
            }
        }
    }

    private void insertHeads(long batchId, List<String> headNos, String now) {
        for (String headNo : headNos) {
            repo.insertHead(batchId, headNo, "PENDING", now);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }
}
