package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.ChildBandDossier;
import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.domain.LabelDrawRecord;
import com.example.starter.cosmetic.domain.PackagingBatchRecord;
import com.example.starter.cosmetic.domain.PrintRunRecord;
import com.example.starter.cosmetic.domain.PrintRunStatus;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.port.IdempotencyPort;
import com.example.starter.cosmetic.port.IdempotencyRow;
import com.example.starter.cosmetic.port.LabelPrintPort;
import com.example.starter.cosmetic.support.FingerprintSupport;
import com.example.starter.cosmetic.support.JsonSupport;
import com.example.starter.cosmetic.support.LabelDigestSupport;
import com.example.starter.cosmetic.web.dto.BandDigestItem;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.DestroyResponse;
import com.example.starter.cosmetic.web.dto.DrawLabelsRequest;
import com.example.starter.cosmetic.web.dto.DrawRecordResponse;
import com.example.starter.cosmetic.web.dto.DrawResponse;
import com.example.starter.cosmetic.web.dto.ExpectedDigestResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResultResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 包装标签印次台账领域服务默认实现。
 *
 * <p>所有写操作以 requestId 幂等：指纹覆盖 requestId 与全部业务字段
 * （含分档摘要集合与业务时刻），同键同参返回首次结果（idempotentReplay=true），
 * 同键异参 409；业务/版本/幂等结果同事务，参数或状态异常整体回滚，失败不占键。</p>
 *
 * <p>领用前重查产品当前有效备案槽：印次绑定的备案修订必须仍是当前有效修订，
 * 新旧修订生效与领用竞争时以印次行上的原子条件 UPDATE 判定，不留旧版生效后
 * 继续领用的凭据。备案换版/注销时由备案事务内调用
 * {@link #disableForFilingRevision} 原子禁用旧印次剩余标签。</p>
 */
@Service
public class LabelPrintServiceImpl implements LabelPrintService {

    private static final String OP_PRINT_REGISTER = "PRINT_REGISTER";
    private static final String OP_LABEL_DRAW = "LABEL_DRAW";
    private static final String OP_LABEL_DESTROY = "LABEL_DESTROY";
    private static final long IDEMPOTENCY_WAIT_NANOS = 10_000_000_000L;
    private static final long IDEMPOTENCY_WAIT_STEP_MS = 20L;

    private final LabelPrintPort labelPrintPort;
    private final FilingPort filingPort;
    private final IdempotencyPort idempotencyPort;
    private final Clock clock;

    public LabelPrintServiceImpl(LabelPrintPort labelPrintPort,
                                 FilingPort filingPort,
                                 IdempotencyPort idempotencyPort,
                                 Clock clock) {
        this.labelPrintPort = labelPrintPort;
        this.filingPort = filingPort;
        this.idempotencyPort = idempotencyPort;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    // ============================ 印次登记 ============================

    @Override
    @Transactional
    public PrintRunResultResponse register(RegisterPrintRunRequest request) {
        Instant now = request.atUtc() == null ? now() : request.atUtc();

        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("printRunNo", request.printRunNo());
        fingerprintFields.put("productCode", request.productCode());
        fingerprintFields.put("printedCount", request.printedCount());
        fingerprintFields.put("labelDigest", request.labelDigest());
        fingerprintFields.put("bandDigests",
                request.bandDigests() == null ? List.of() : request.bandDigests());
        fingerprintFields.put("atUtc", request.atUtc() == null ? null : request.atUtc().toString());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<PrintRunResultResponse> replay = resolveIdempotency(
                request.requestId(), OP_PRINT_REGISTER, fingerprint, PrintRunResultResponse.class,
                r -> new PrintRunResultResponse(r.httpStatus(), r.printRun(), true, r.atUtc()));
        if (replay.isPresent()) {
            return replay.get();
        }

        FilingRecord filing = currentEffectiveFiling(request.productCode());
        Map<String, String> expectedBands = expectedBandDigests(filing);
        String expectedDigest = expectedLabelDigest(filing, expectedBands);

        if (filing.isChild()) {
            validateChildBandDigests(request, expectedBands);
        } else if (request.bandDigests() != null && !request.bandDigests().isEmpty()) {
            throw new IllegalArgumentException("成人备案印次不得携带分龄档标识摘要");
        }
        if (!request.labelDigest().equals(expectedDigest)) {
            throw new UnprocessableException("LABEL_DIGEST_MISMATCH",
                    "标签文本摘要与备案 " + filing.filingNo() + "#" + filing.versionNo()
                            + " 受理冻结内容不一致，拒绝登记印次");
        }

        PrintRunRecord record = new PrintRunRecord(
                null, request.printRunNo(), request.productCode(),
                filing.filingNo(), filing.versionNo(), expectedDigest,
                filing.isChild() ? expectedBands : null,
                request.printedCount(), 0, 0, PrintRunStatus.ACTIVE, 1,
                request.requestId(), null, null, now, now);
        // 同印次号唯一：冲突由数据库唯一约束抛出（HTTP 层映射 409）
        labelPrintPort.insertPrintRun(record);

        PrintRunRecord saved = labelPrintPort.findPrintRun(request.printRunNo()).orElseThrow();
        PrintRunResultResponse response = new PrintRunResultResponse(
                200, PrintRunResponse.from(saved), false, now);
        idempotencyPort.complete(request.requestId(), 200, PrintRunStatus.ACTIVE.name(),
                request.printRunNo(), JsonSupport.toJson(response), now);
        return response;
    }

    /** 产品当前有效备案（未生效备案拒绝登记印次）。 */
    private FilingRecord currentEffectiveFiling(String productCode) {
        ActiveSlot slot = filingPort.findActiveSlot(productCode)
                .orElseThrow(() -> new UnprocessableException("NO_EFFECTIVE_FILING",
                        "产品当前无已生效备案，不能登记印次: " + productCode));
        FilingRecord filing = filingPort.findVersion(slot.filingNo(), slot.versionNo())
                .orElseThrow(() -> new IllegalStateException("有效槽指向的备案版本不存在"));
        if (filing.status() != FilingStatus.EFFECTIVE) {
            throw new UnprocessableException("FILING_NOT_EFFECTIVE",
                    "备案 " + slot.filingNo() + "#" + slot.versionNo()
                            + " 未生效（当前状态 " + filing.status() + "），不能登记印次");
        }
        return filing;
    }

    /** 备案受理冻结内容对应的逐档期望摘要；成人备案返回 null。 */
    private Map<String, String> expectedBandDigests(FilingRecord filing) {
        if (!filing.isChild()) {
            return null;
        }
        Map<String, String> bands = new LinkedHashMap<>();
        for (ChildBandDossier dossier : filing.childDossiers()) {
            bands.put(dossier.bandCode(), LabelDigestSupport.childBandDigest(
                    filing.filingNo(), filing.versionNo(), dossier.bandCode(), dossier.labelText()));
        }
        return bands;
    }

    /** 印次级期望摘要：儿童为逐档摘要集合的结构化摘要，成人为备案级摘要。 */
    private String expectedLabelDigest(FilingRecord filing, Map<String, String> expectedBands) {
        if (filing.isChild()) {
            return LabelDigestSupport.childDigestSet(expectedBands);
        }
        return LabelDigestSupport.adultLabelDigest(
                filing.filingNo(), filing.versionNo(), filing.claims(), filing.packagingText());
    }

    /** 儿童印次逐档核对：缺档/多档/摘要错一次列全拒绝（不能只拿一个全局标识代替）。 */
    private void validateChildBandDigests(RegisterPrintRunRequest request,
                                          Map<String, String> expectedBands) {
        Map<String, String> supplied = new LinkedHashMap<>();
        for (BandDigestItem item : request.bandDigests() == null
                ? List.<BandDigestItem>of() : request.bandDigests()) {
            if (supplied.put(item.bandCode(), item.labelDigest()) != null) {
                throw new IllegalArgumentException("分龄档标识摘要重复: " + item.bandCode());
            }
        }
        List<String> missing = expectedBands.keySet().stream()
                .filter(b -> !supplied.containsKey(b)).toList();
        if (!missing.isEmpty()) {
            throw new UnprocessableException("BAND_MISSING",
                    "儿童印次缺少分龄档独立标识摘要: " + String.join(", ", missing),
                    missing.stream().map(b -> "缺少分龄档 " + b + " 的标识摘要").toList());
        }
        List<String> unexpected = supplied.keySet().stream()
                .filter(b -> !expectedBands.containsKey(b)).toList();
        if (!unexpected.isEmpty()) {
            throw new UnprocessableException("BAND_UNEXPECTED",
                    "儿童印次携带备案分龄档之外的标识摘要: " + String.join(", ", unexpected),
                    unexpected.stream().map(b -> "分龄档 " + b + " 不在备案受理冻结范围内").toList());
        }
        List<String> mismatched = expectedBands.keySet().stream()
                .filter(b -> !expectedBands.get(b).equals(supplied.get(b))).toList();
        if (!mismatched.isEmpty()) {
            throw new UnprocessableException("BAND_DIGEST_MISMATCH",
                    "儿童印次分龄档标识摘要与备案受理冻结内容不一致: "
                            + String.join(", ", mismatched),
                    mismatched.stream().map(b -> "分龄档 " + b + " 标识摘要不一致").toList());
        }
    }

    // ============================ 领用 ============================

    @Override
    @Transactional
    public DrawResponse draw(String printRunNo, DrawLabelsRequest request) {
        Instant now = request.atUtc() == null ? now() : request.atUtc();

        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("printRunNo", printRunNo);
        fingerprintFields.put("batchNo", request.batchNo());
        fingerprintFields.put("quantity", request.quantity());
        fingerprintFields.put("expectedVersion", request.expectedVersion());
        fingerprintFields.put("atUtc", request.atUtc() == null ? null : request.atUtc().toString());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<DrawResponse> replay = resolveIdempotency(
                request.requestId(), OP_LABEL_DRAW, fingerprint, DrawResponse.class,
                r -> new DrawResponse(r.httpStatus(), r.printRunNo(), r.batchNo(), r.productCode(),
                        r.quantity(), r.printRunVersion(), r.remainingAfter(), true, r.atUtc()));
        if (replay.isPresent()) {
            return replay.get();
        }

        PrintRunRecord run = labelPrintPort.findPrintRun(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo));

        if (run.status() != PrintRunStatus.ACTIVE) {
            throw new UnprocessableException("PRINT_RUN_DISABLED",
                    "印次 " + printRunNo + " 已禁用（" + run.disabledReason() + "），剩余标签不可领用");
        }
        // 重查当前有效备案：印次绑定的备案修订必须仍是当前有效修订，
        // 新旧修订生效与领用竞争时不留旧版生效后继续领用的凭据（兜底；
        // 正常路径下换版/注销已在同事务禁用旧印次，由上方状态检查拦截）
        Optional<ActiveSlot> slot = filingPort.findActiveSlot(run.productCode());
        if (slot.isEmpty() || !slot.get().filingNo().equals(run.filingNo())
                || slot.get().versionNo() != run.filingVersionNo()) {
            throw new UnprocessableException("FILING_REVISION_STALE",
                    "印次 " + printRunNo + " 绑定的备案修订 " + run.filingNo() + "#"
                            + run.filingVersionNo() + " 已非当前有效修订，禁止继续领用");
        }
        if (run.version() != request.expectedVersion()) {
            throw new ConflictException("VERSION_CONFLICT",
                    "expectedVersion=" + request.expectedVersion()
                            + " 与当前印次版本 " + run.version() + " 不一致（409）");
        }
        if (request.quantity() > run.remainingCount()) {
            throw new UnprocessableException("INSUFFICIENT_BALANCE",
                    "领用张数 " + request.quantity() + " 超过未领余额 " + run.remainingCount());
        }

        registerBatchIfAbsent(request.batchNo(), run.productCode(), now);

        boolean drawn = labelPrintPort.drawIfActive(printRunNo, request.expectedVersion(),
                request.quantity(), now);
        if (!drawn) {
            // 并发下状态/版本/余额已变化：重读判定具体原因
            PrintRunRecord current = labelPrintPort.findPrintRun(printRunNo).orElseThrow();
            if (current.status() != PrintRunStatus.ACTIVE) {
                throw new UnprocessableException("PRINT_RUN_DISABLED",
                        "印次 " + printRunNo + " 已禁用（" + current.disabledReason()
                                + "），剩余标签不可领用");
            }
            if (current.version() != request.expectedVersion()) {
                throw new ConflictException("VERSION_CONFLICT",
                        "expectedVersion=" + request.expectedVersion()
                                + " 与当前印次版本 " + current.version() + " 不一致（409）");
            }
            throw new UnprocessableException("INSUFFICIENT_BALANCE",
                    "领用张数 " + request.quantity() + " 超过未领余额 " + current.remainingCount());
        }

        labelPrintPort.insertDraw(new LabelDrawRecord(null, printRunNo, request.batchNo(),
                run.productCode(), request.quantity(), request.requestId(), now,
                false, null, null));

        DrawResponse response = new DrawResponse(200, printRunNo, request.batchNo(),
                run.productCode(), request.quantity(), request.expectedVersion() + 1,
                run.remainingCount() - request.quantity(), false, now);
        idempotencyPort.complete(request.requestId(), 200, "DRAWN", printRunNo,
                JsonSupport.toJson(response), now);
        return response;
    }

    /** 包装批首次使用登记所属产品；跨产品借用拒绝；并发首登冲突时重读校验。 */
    private void registerBatchIfAbsent(String batchNo, String productCode, Instant now) {
        Optional<PackagingBatchRecord> existing = labelPrintPort.findBatch(batchNo);
        if (existing.isEmpty()) {
            try {
                labelPrintPort.insertBatch(new PackagingBatchRecord(batchNo, productCode, now));
                return;
            } catch (DataIntegrityViolationException e) {
                // 并发首登同一批号：另一个事务已登记，重读校验产品归属
                existing = labelPrintPort.findBatch(batchNo);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        PackagingBatchRecord batch = existing.orElseThrow();
        if (!batch.productCode().equals(productCode)) {
            throw new UnprocessableException("CROSS_PRODUCT_BATCH",
                    "包装批号 " + batchNo + " 已登记属于产品 " + batch.productCode()
                            + "，跨产品借用拒绝（当前产品 " + productCode + "）");
        }
    }

    // ============================ 销毁 ============================

    @Override
    @Transactional
    public DestroyResponse destroy(String printRunNo, DestroyLabelsRequest request) {
        Instant now = request.atUtc() == null ? now() : request.atUtc();

        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("printRunNo", printRunNo);
        fingerprintFields.put("quantity", request.quantity());
        fingerprintFields.put("expectedVersion", request.expectedVersion());
        fingerprintFields.put("atUtc", request.atUtc() == null ? null : request.atUtc().toString());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<DestroyResponse> replay = resolveIdempotency(
                request.requestId(), OP_LABEL_DESTROY, fingerprint, DestroyResponse.class,
                r -> new DestroyResponse(r.httpStatus(), r.printRunNo(), r.quantity(),
                        r.printRunVersion(), r.remainingAfter(), true, r.atUtc()));
        if (replay.isPresent()) {
            return replay.get();
        }

        PrintRunRecord run = labelPrintPort.findPrintRun(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo));
        if (run.version() != request.expectedVersion()) {
            throw new ConflictException("VERSION_CONFLICT",
                    "expectedVersion=" + request.expectedVersion()
                            + " 与当前印次版本 " + run.version() + " 不一致（409）");
        }
        // 仅未领余额可销毁：已领部分不能销毁退回余额；禁用剩余可销毁但不复活
        if (request.quantity() > run.remainingCount()) {
            throw new UnprocessableException("INSUFFICIENT_REMAINING",
                    "销毁张数 " + request.quantity() + " 超过未领余额 " + run.remainingCount()
                            + "（已领部分不能销毁退回余额）");
        }

        boolean destroyed = labelPrintPort.destroyIfEnough(printRunNo, request.expectedVersion(),
                request.quantity(), now);
        if (!destroyed) {
            PrintRunRecord current = labelPrintPort.findPrintRun(printRunNo).orElseThrow();
            if (current.version() != request.expectedVersion()) {
                throw new ConflictException("VERSION_CONFLICT",
                        "expectedVersion=" + request.expectedVersion()
                                + " 与当前印次版本 " + current.version() + " 不一致（409）");
            }
            throw new UnprocessableException("INSUFFICIENT_REMAINING",
                    "销毁张数 " + request.quantity() + " 超过未领余额 " + current.remainingCount()
                            + "（已领部分不能销毁退回余额）");
        }

        labelPrintPort.insertDestroy(printRunNo, request.quantity(), request.requestId(), now);

        DestroyResponse response = new DestroyResponse(200, printRunNo, request.quantity(),
                request.expectedVersion() + 1, run.remainingCount() - request.quantity(),
                false, now);
        idempotencyPort.complete(request.requestId(), 200, "DESTROYED", printRunNo,
                JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 查询 ============================

    @Override
    @Transactional(readOnly = true)
    public PrintRunResponse getPrintRun(String printRunNo) {
        return PrintRunResponse.from(labelPrintPort.findPrintRun(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DrawRecordResponse> listDraws(String printRunNo) {
        labelPrintPort.findPrintRun(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo));
        return labelPrintPort.findDrawsByPrintRun(printRunNo).stream()
                .map(DrawRecordResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ExpectedDigestResponse expectedDigest(String productCode) {
        FilingRecord filing = currentEffectiveFiling(productCode);
        Map<String, String> bands = expectedBandDigests(filing);
        return new ExpectedDigestResponse(productCode, filing.filingNo(), filing.versionNo(),
                expectedLabelDigest(filing, bands), bands);
    }

    // ============================ 备案失效联动 ============================

    @Override
    @Transactional
    public void disableForFilingRevision(String filingNo, int filingVersionNo,
                                         String reason, Instant now) {
        // 与备案状态变更同事务：原子禁用旧印次剩余标签；已领用只追加风险关联，不抹历史
        labelPrintPort.disableActiveByFiling(filingNo, filingVersionNo, reason, now);
        labelPrintPort.flagRiskByFiling(filingNo, filingVersionNo,
                "所属备案修订已失效（" + reason + "），已领标签追加风险关联", now);
    }

    // ============================ 幂等辅助 ============================

    private <T> Optional<T> resolveIdempotency(String requestId, String operation,
                                               String fingerprint, Class<T> type,
                                               UnaryOperator<T> replayMarker) {
        Optional<IdempotencyRow> existing = idempotencyPort.find(requestId);
        if (existing.isEmpty()) {
            try {
                idempotencyPort.claim(requestId, operation, fingerprint);
                return Optional.empty();
            } catch (DataIntegrityViolationException e) {
                // 并发同键：另一个事务刚刚插入主张行，落入下方重读判定
                existing = idempotencyPort.find(requestId);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }
        IdempotencyRow row = existing.orElseThrow();
        if (!row.operation().equals(operation)) {
            throw new ConflictException("IDEMPOTENCY_KEY_CROSSED_OPERATION",
                    "requestId 已用于其它操作类型: " + row.operation());
        }
        if (!row.fingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_PARAM_MISMATCH",
                    "同一 requestId 对应不同业务参数，拒绝处理（409）");
        }
        // 同键同参但首个事务尚未回填（并发重放）：等待首个事务完成后返回首次结果
        if (row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty()) {
            row = awaitFirstCompletion(requestId);
        }
        return Optional.of(replayMarker.apply(JsonSupport.fromJson(row.responseJson(), type)));
    }

    /**
     * 并发重放时等待首个事务回填响应；带超时，避免首个事务异常时无限等待。
     */
    private IdempotencyRow awaitFirstCompletion(String requestId) {
        long deadline = System.nanoTime() + IDEMPOTENCY_WAIT_NANOS;
        IdempotencyRow row;
        do {
            try {
                Thread.sleep(IDEMPOTENCY_WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("IDEMPOTENCY_WAIT_INTERRUPTED",
                        "等待首次处理结果时被中断: " + requestId);
            }
            row = idempotencyPort.find(requestId).orElseThrow(() ->
                    new ConflictException("IDEMPOTENCY_RECORD_VANISHED", "幂等记录消失: " + requestId));
        } while ((row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty())
                && System.nanoTime() < deadline);

        if (row.httpStatus() == 0 || row.responseJson() == null || row.responseJson().isEmpty()) {
            throw new ConflictException("IDEMPOTENCY_FIRST_RESULT_TIMEOUT",
                    "等待首次处理结果超时: " + requestId);
        }
        return row;
    }
}
