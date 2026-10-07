package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.JudgmentCategory;
import com.example.starter.domain.TruckManifestStatus;
import com.example.starter.error.ApiException;
import com.example.starter.repo.BatchRepository;
import com.example.starter.repo.BatchRepository.BatchRow;
import com.example.starter.repo.BatchRepository.HeadRow;
import com.example.starter.repo.BatchRepository.JudgmentRow;
import com.example.starter.repo.BatchRepository.ReceiptRow;
import com.example.starter.repo.TruckRepository;
import com.example.starter.repo.TruckRepository.ManifestRow;
import com.example.starter.repo.TruckRepository.OccupationRow;
import com.example.starter.web.dto.ReconciliationEntry;
import com.example.starter.web.dto.TruckCreateRequest;
import com.example.starter.web.dto.TruckManifestActionRequest;
import com.example.starter.web.dto.TruckManifestResponse;
import com.example.starter.web.dto.TruckManifestResponse.TruckProgress;
import com.example.starter.web.dto.TruckProgressView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分车出厂服务：同一合成屠宰批次多车出厂，按头只全量预占、冻结清单、逐车核销。
 *
 * <p>并发控制沿用原门禁：每个写事务先 {@code SELECT ... FOR UPDATE} 锁定批次主行，
 * 与风险变更/结果、原整批核销同序互斥；再锁目标车单行。头只争车由
 * {@code (batch_id, head_no)} 唯一约束兜底，同头只争车只一方成功。预占、核销、
 * 批次终态与幂等登记同事务，业务失败整体回滚不占键，撤销与发车不会既释放又出厂。
 */
@org.springframework.stereotype.Service
public class TruckService {

    private final BatchRepository batchRepo;
    private final TruckRepository truckRepo;
    private final RiskService riskService;
    private final IdempotentExecutor idempotency;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TruckService(BatchRepository batchRepo, TruckRepository truckRepo, RiskService riskService,
                        IdempotentExecutor idempotency, Clock clock, ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.truckRepo = truckRepo;
        this.riskService = riskService;
        this.idempotency = idempotency;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ---------- 创建分车清单（全量预占冻结） ----------

    /** 创建分车清单：全量预占合格头只并冻结，缺一项全单不占，创建后清单冻结（幂等）。 */
    @Transactional
    public TruckManifestResponse createManifest(String batchNo, TruckCreateRequest req) {
        // 路径批次号纳入结构化指纹，避免同 requestId 同体跨批误重放；车号与完整头只已在请求体内。
        return idempotency.run("truck-create", req.requestId(), new FingerprintEnvelope(batchNo, null, req),
                TruckManifestResponse.class, () -> doCreate(batchNo, req));
    }

    private TruckManifestResponse doCreate(String batchNo, TruckCreateRequest req) {
        String occurredAt = parseOccurredAt(req.occurredAt());
        List<String> requested = normalizeHeads(req.headNos());
        BatchRow batch = lockAndReload(batchNo);
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            throw ApiException.conflict("批次已最终出厂，不再受理分车清单");
        }
        // 批次层面仅要求“有合格头只且尚未出厂”：判定完成态为 JUDGED 或 SPECIAL_QUARANTINE。
        // 专项隔离批允许建立清单（预占），风险门禁在逐车核销时才阻断，不在这里提前拒绝。
        if (!BatchStatus.JUDGED.name().equals(batch.status())
                && !BatchStatus.SPECIAL_QUARANTINE.name().equals(batch.status())) {
            throw ApiException.conflict("批次未完成判定分流，不允许启用分车");
        }
        List<HeadRow> heads = batchRepo.findHeads(batch.id());
        Map<String, HeadRow> ledger = new java.util.LinkedHashMap<>();
        for (HeadRow head : heads) {
            ledger.put(head.headNo(), head);
        }
        Set<String> qualified = qualifiedHeads(batch.id());
        if (qualified.isEmpty()) {
            throw ApiException.unprocessable("批次没有合格（QUALIFIED）头只，不允许启用分车");
        }
        // 出厂合格头只集合取原判定明细（QUALIFIED）：跨批/不存在、病害处置或非合格头只一律拒绝。
        for (String headNo : requested) {
            HeadRow head = ledger.get(headNo);
            if (head == null) {
                throw ApiException.unprocessable("头只不属于本批（跨批或台账不存在），拒绝装车：" + headNo);
            }
            if (!qualified.contains(headNo)) {
                throw ApiException.unprocessable("病害处置或非合格头只拒绝装车：" + headNo
                        + "（判定 " + head.judgmentCategory() + "）");
            }
        }
        // 车号批次内唯一，已撤销车单的车号也不可复用。
        if (truckRepo.findManifestByTruck(batch.id(), req.truckNo()).isPresent()) {
            throw ApiException.conflict("车号已存在（含已撤销车单，车号不可复用）：" + req.truckNo());
        }
        // 活跃占用唯一：已被其他未撤销车辆预占或已随车出厂的头只拒绝；持批次行锁后可靠复算。
        Set<String> occupied = new LinkedHashSet<>();
        for (OccupationRow occupation : truckRepo.findOccupations(batch.id())) {
            occupied.add(occupation.headNo());
        }
        for (String headNo : requested) {
            if (occupied.contains(headNo)) {
                throw ApiException.conflict("头只已被其他未撤销车辆占用或已随车出厂，拒绝装车：" + headNo);
            }
        }
        String frozenJson = toJson(requested);
        long manifestId;
        try {
            manifestId = truckRepo.insertManifest(batch.id(), req.truckNo(), requested.size(),
                    frozenJson, occurredAt);
            for (String headNo : requested) {
                truckRepo.insertOccupation(batch.id(), manifestId, headNo,
                        TruckManifestStatus.RESERVED.name(), occurredAt);
            }
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底（车号或头只争车）：整单回滚，缺一项全单不占。
            throw ApiException.conflict("分车清单预占冲突：车号或头只已被占用，全单不占");
        }
        ManifestRow manifest = truckRepo.findManifestByTruck(batch.id(), req.truckNo()).orElseThrow();
        return toResponse(batch, manifest, requested);
    }

    // ---------- 整体撤销 ----------

    /** 整体撤销未发车清单：释放全部头只预占，仅 RESERVED 可撤销，版本加 1（幂等）。 */
    @Transactional
    public TruckManifestResponse revokeManifest(String batchNo, String truckNo,
                                                TruckManifestActionRequest req) {
        // 路径批次号与车号纳入指纹：同一 requestId 同体作用于不同车单须判为异参 409。
        return idempotency.run("truck-revoke", req.requestId(),
                new FingerprintEnvelope(batchNo, truckNo, req), TruckManifestResponse.class,
                () -> doRevoke(batchNo, truckNo, req));
    }

    private TruckManifestResponse doRevoke(String batchNo, String truckNo,
                                           TruckManifestActionRequest req) {
        String occurredAt = parseOccurredAt(req.occurredAt());
        BatchRow batch = lockAndReload(batchNo);
        ManifestRow manifest = lockManifest(batch, truckNo);
        if (TruckManifestStatus.RELEASED.name().equals(manifest.status())) {
            throw ApiException.conflict("车辆已发车核销，不可撤销");
        }
        if (TruckManifestStatus.REVOKED.name().equals(manifest.status())) {
            throw ApiException.conflict("车单已整体撤销，终态不可改，不能事后改单");
        }
        if (req.expectedVersion() != manifest.version()) {
            throw ApiException.conflict("车单版本不匹配：expectedVersion=" + req.expectedVersion()
                    + "，当前版本=" + manifest.version());
        }
        int revoked = truckRepo.markRevoked(manifest.id(), manifest.version(), occurredAt);
        if (revoked == 0) {
            throw ApiException.conflict("并发冲突：车单状态或版本已变化，撤销失败");
        }
        int released = truckRepo.deleteOccupationsByManifest(manifest.id());
        if (released != manifest.headCount()) {
            throw ApiException.conflict("车单预占头只数与冻结清单不一致，撤销整体回滚");
        }
        ManifestRow after = truckRepo.findManifestByTruck(batch.id(), truckNo).orElseThrow();
        return toResponse(batch, after, fromJson(after.frozenHeads()));
    }

    // ---------- 逐车发车核销 ----------

    /** 逐车核销：事务内重查抽检与处置门禁、车单与版本，满足才整车转已出厂；末车恰核销完批次转终态（幂等）。 */
    @Transactional
    public TruckManifestResponse dispatchManifest(String batchNo, String truckNo,
                                                  TruckManifestActionRequest req) {
        // 路径批次号与车号纳入指纹：完整头只冻结于车单，版本/UTC 时刻在请求体内。
        return idempotency.run("truck-dispatch", req.requestId(),
                new FingerprintEnvelope(batchNo, truckNo, req), TruckManifestResponse.class,
                () -> doDispatch(batchNo, truckNo, req));
    }

    private TruckManifestResponse doDispatch(String batchNo, String truckNo,
                                             TruckManifestActionRequest req) {
        String occurredAt = parseOccurredAt(req.occurredAt());
        BatchRow batch = lockAndReload(batchNo);
        ManifestRow manifest = lockManifest(batch, truckNo);
        // 已发车车辆不可改：无新 requestId 的重复核销按幂等成功返回当前车单结果。
        if (TruckManifestStatus.RELEASED.name().equals(manifest.status())) {
            return toResponse(reload(batch.id()), manifest, fromJson(manifest.frozenHeads()));
        }
        if (TruckManifestStatus.REVOKED.name().equals(manifest.status())) {
            throw ApiException.conflict("车单已整体撤销并释放，不能发车核销");
        }
        if (BatchStatus.RELEASED.name().equals(batch.status())) {
            throw ApiException.conflict("批次已最终出厂，存在未发车车单属异常状态，拒绝核销");
        }
        if (BatchStatus.SPECIAL_QUARANTINE.name().equals(batch.status())) {
            throw ApiException.conflict("批次处于专项隔离，四类判定保留但受控出厂拒绝");
        }
        if (!BatchStatus.JUDGED.name().equals(batch.status())) {
            throw ApiException.conflict("批次未达到可核销状态，不允许逐车发车");
        }
        if (req.expectedVersion() != manifest.version()) {
            throw ApiException.conflict("车单版本不匹配：expectedVersion=" + req.expectedVersion()
                    + "，当前版本=" + manifest.version());
        }
        // 重查无害化处置门禁：化制/销毁回执头只数与判定数恰等。
        assertReceiptsMatched(batch.id());
        // 重查风险抽检门禁：受控批次全部清单完成且全部样本阴性才放行（调用方持批次行锁）。
        riskService.assertReleaseGate(batch.id());
        // 重查本车冻结清单与活跃预占：整车头只仍全部属于本车 RESERVED 预占，缺一不核销、保留原预占。
        List<String> frozen = fromJson(manifest.frozenHeads());
        Set<String> reservedForTruck = new LinkedHashSet<>();
        for (OccupationRow occupation : truckRepo.findOccupations(batch.id())) {
            if (occupation.manifestId() == manifest.id()
                    && TruckManifestStatus.RESERVED.name().equals(occupation.status())) {
                reservedForTruck.add(occupation.headNo());
            }
        }
        if (reservedForTruck.size() != frozen.size() || !reservedForTruck.containsAll(frozen)) {
            throw ApiException.conflict("车单冻结头只与当前预占不一致，拒绝核销并保留原预占");
        }
        // 单车整车核销：占用与车单同事务流转，任一不符整体回滚，不存在部分核销。
        int occupiedReleased = truckRepo.markOccupationsReleased(manifest.id());
        if (occupiedReleased != frozen.size()) {
            throw ApiException.conflict("单车只允许整车核销：预占头只数不符，整体回滚");
        }
        int dispatched = truckRepo.markDispatched(manifest.id(), manifest.version(), occurredAt);
        if (dispatched == 0) {
            throw ApiException.conflict("并发冲突：车单状态或版本已变化，发车核销失败");
        }
        // 末车收口：全部合格头只恰核销完才把原批转最终出厂；病害头只不计入未出厂人数。
        BatchRow latest = reload(batch.id());
        TruckProgress progress = buildProgress(latest);
        if (progress.qualifiedTotal() > 0
                && progress.shippedCount() == progress.qualifiedTotal()) {
            int updated = batchRepo.markReleased(batch.id(), occurredAt);
            if (updated == 0) {
                throw ApiException.conflict("并发冲突：批次终态收口失败");
            }
            latest = reload(batch.id());
        }
        ManifestRow after = truckRepo.findManifestByTruck(batch.id(), truckNo).orElseThrow();
        return toResponse(latest, after, frozen);
    }

    // ---------- 分车进度查询 ----------

    /** 分车进度查询：每车冻结清单、按头只勾稽的已出厂/未出厂头只及批次进度。 */
    @Transactional(readOnly = true)
    public TruckProgressView getProgress(String batchNo) {
        BatchRow batch = batchRepo.findBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + batchNo));
        boolean enabled = truckRepo.countManifests(batch.id()) > 0;
        Set<String> qualified = qualifiedHeads(batch.id());
        Set<String> shipped = new TreeSet<>();
        for (OccupationRow occupation : truckRepo.findOccupations(batch.id())) {
            if (TruckManifestStatus.RELEASED.name().equals(occupation.status())) {
                shipped.add(occupation.headNo());
            }
        }
        Set<String> unshipped = new TreeSet<>(qualified);
        unshipped.removeAll(shipped);
        List<TruckProgressView.ManifestView> manifestViews = new ArrayList<>();
        for (ManifestRow manifest : truckRepo.findManifests(batch.id())) {
            manifestViews.add(new TruckProgressView.ManifestView(manifest.truckNo(), manifest.version(),
                    manifest.status(), fromJson(manifest.frozenHeads()), manifest.createdAt(),
                    manifest.revokedAt(), manifest.dispatchedAt()));
        }
        List<String> qualifiedSorted = new ArrayList<>(qualified);
        List<String> shippedSorted = new ArrayList<>(shipped);
        return new TruckProgressView(batch.batchNo(), batch.status(), enabled, qualifiedSorted,
                shippedSorted, new ArrayList<>(unshipped), manifestViews, buildProgress(batch));
    }

    // ---------- 内部：门禁与进度 ----------

    /** 处置门禁复算：化制/销毁类别回执头只数（不含迟到）与判定数恰等，否则拒绝出厂。 */
    private void assertReceiptsMatched(long batchId) {
        List<ReconciliationEntry> reconciliation = reconciliation(batchId);
        List<String> mismatched = reconciliation.stream()
                .filter(e -> !e.matched())
                .map(e -> e.category() + "(判定 " + e.judgedCount() + " / 回执 " + e.receivedCount() + ")")
                .toList();
        if (!mismatched.isEmpty()) {
            throw ApiException.conflict("回执头只数与判定数不一致，拒绝逐车出厂：" + mismatched);
        }
    }

    /** 回执勾稽：按化制/销毁类别对比判定头只数与已登记回执头只数（不含迟到回执），恰等一致。 */
    private List<ReconciliationEntry> reconciliation(long batchId) {
        List<JudgmentRow> judgments = batchRepo.findJudgments(batchId);
        List<ReceiptRow> receipts = batchRepo.findReceipts(batchId);
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

    /** 按头只勾稽的批次进度：仅统计合格头只，病害处置头只不计入未出厂。 */
    private TruckProgress buildProgress(BatchRow batch) {
        Set<String> qualified = qualifiedHeads(batch.id());
        int shipped = 0;
        for (OccupationRow occupation : truckRepo.findOccupations(batch.id())) {
            if (TruckManifestStatus.RELEASED.name().equals(occupation.status())
                    && qualified.contains(occupation.headNo())) {
                shipped++;
            }
        }
        boolean enabled = truckRepo.countManifests(batch.id()) > 0;
        boolean released = BatchStatus.RELEASED.name().equals(batch.status());
        return new TruckProgress(enabled, qualified.size(), shipped, qualified.size() - shipped, released);
    }

    /** 本批合格（QUALIFIED）头只集合，取自原判定明细，字典序。 */
    private Set<String> qualifiedHeads(long batchId) {
        Set<String> qualified = new TreeSet<>();
        for (JudgmentRow judgment : batchRepo.findJudgments(batchId)) {
            if (JudgmentCategory.QUALIFIED.name().equals(judgment.category())) {
                qualified.add(judgment.headNo());
            }
        }
        return qualified;
    }

    // ---------- 内部：加锁 / 组装 / 工具 ----------

    /** 锁定批次主行并重读最新状态，与风险变更/结果、原整批核销同序互斥。 */
    private BatchRow lockAndReload(String batchNo) {
        BatchRow batch = batchRepo.findBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("批次不存在：" + batchNo));
        batchRepo.lockBatch(batch.id());
        return batchRepo.findBatchByNo(batchNo).orElseThrow();
    }

    private BatchRow reload(long batchId) {
        return batchRepo.findBatchById(batchId).orElseThrow();
    }

    /** 行锁读取目标车单，不存在返回 404。 */
    private ManifestRow lockManifest(BatchRow batch, String truckNo) {
        return truckRepo.findManifestByTruckForUpdate(batch.id(), truckNo)
                .orElseThrow(() -> ApiException.notFound(
                        "分车清单不存在：批次 " + batch.batchNo() + " 车号 " + truckNo));
    }

    private TruckManifestResponse toResponse(BatchRow batch, ManifestRow manifest, List<String> frozenHeads) {
        return new TruckManifestResponse(batch.batchNo(), manifest.truckNo(), manifest.version(),
                manifest.status(), frozenHeads, manifest.createdAt(), manifest.revokedAt(),
                manifest.dispatchedAt(), buildProgress(batch));
    }

    /** 头只编号去重并保留请求顺序（冻结顺序即装车顺序）。 */
    private List<String> normalizeHeads(List<String> headNos) {
        Set<String> seen = new LinkedHashSet<>();
        for (String headNo : headNos) {
            if (!seen.add(headNo)) {
                throw ApiException.badRequest("车单头只编号重复：" + headNo);
            }
        }
        return List.copyOf(seen);
    }

    private String parseOccurredAt(String occurredAt) {
        try {
            return Instant.parse(occurredAt).toString();
        } catch (Exception e) {
            throw ApiException.badRequest("occurredAt 须为 UTC ISO-8601 时刻：" + occurredAt);
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("冻结清单序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("冻结清单反序列化失败", e);
        }
    }

    /**
     * 幂等指纹信封：把路径上的批次号、车号与请求体（含完整头只、expectedVersion、UTC 时刻）
     * 一并结构化序列化，杜绝同 requestId 同体跨车单/跨批误重放。
     * 创建请求车号已在请求体内，truckNo 传 null。
     */
    private record FingerprintEnvelope(String batchNo, String truckNo, Object request) {
    }
}
