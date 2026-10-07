package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.domain.ChildBandDossier;
import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.domain.LabelDestruction;
import com.example.starter.cosmetic.domain.LabelIssue;
import com.example.starter.cosmetic.domain.LabelPrintRun;
import com.example.starter.cosmetic.domain.PackagingBatch;
import com.example.starter.cosmetic.domain.PrintRunStatus;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.port.IdempotencyPort;
import com.example.starter.cosmetic.port.IdempotencyRow;
import com.example.starter.cosmetic.port.LabelPrintPort;
import com.example.starter.cosmetic.support.FingerprintSupport;
import com.example.starter.cosmetic.support.JsonSupport;
import com.example.starter.cosmetic.support.LabelDigestSupport;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.IssueLabelsRequest;
import com.example.starter.cosmetic.web.dto.LabelDestructionResponse;
import com.example.starter.cosmetic.web.dto.LabelDestructionResultResponse;
import com.example.starter.cosmetic.web.dto.LabelIssueResponse;
import com.example.starter.cosmetic.web.dto.LabelIssueResultResponse;
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
 * 包装标签印次台账服务默认实现。
 *
 * <p>所有写操作以 requestId 幂等：指纹覆盖 requestId、完整参数与分档集合，
 * 同键同参重放首次结果，同键异参 409；领用/销毁/风险关联/余额与幂等记录同事务，
 * 失败整体回滚不占键。</p>
 *
 * <p>领用前在事务内锁定印次行并重查当前有效备案槽：备案换版（实质变更生效）
 * 或注销已在同事务内原子禁用旧印次，锁保证不会留下旧版生效后继续领用的凭据。</p>
 */
@Service
public class LabelPrintServiceImpl implements LabelPrintService {

    private static final String OP_REGISTER = "PR_REGISTER";
    private static final String OP_ISSUE = "PR_ISSUE";
    private static final String OP_DESTROY = "PR_DESTROY";
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
        Instant now = now();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("printRunNo", request.printRunNo());
        fingerprintFields.put("productCode", request.productCode());
        fingerprintFields.put("labelDigest", request.labelDigest());
        fingerprintFields.put("bandDigests",
                request.bandDigests() == null ? Map.of() : request.bandDigests());
        fingerprintFields.put("printedCount", request.printedCount());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<PrintRunResultResponse> replay = resolveIdempotency(
                request.requestId(), OP_REGISTER, fingerprint,
                PrintRunResultResponse.class, PrintRunResultResponse::asReplay);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 引用当前有效备案的具体修订；未生效备案拒绝
        ActiveSlot slot = filingPort.findActiveSlot(request.productCode())
                .orElseThrow(() -> new UnprocessableException("FILING_NOT_EFFECTIVE",
                        "产品当前无已生效备案，印次登记拒绝: " + request.productCode()));
        FilingRecord filing = filingPort.findVersion(slot.filingNo(), slot.versionNo())
                .orElseThrow(() -> new IllegalStateException("有效槽指向的备案版本不存在"));
        if (filing.status() != FilingStatus.EFFECTIVE) {
            throw new UnprocessableException("FILING_NOT_EFFECTIVE",
                    "备案 " + filing.filingNo() + " 当前状态非生效: " + filing.status());
        }

        Map<String, String> bandDigests;
        if (filing.isChild()) {
            bandDigests = validateChildDigests(request, filing);
        } else {
            if (request.bandDigests() != null && !request.bandDigests().isEmpty()) {
                throw new IllegalArgumentException("成人印次不得携带分龄档标识摘要");
            }
            bandDigests = null;
            String expected = LabelDigestSupport.adultDigest(filing.packagingText());
            if (!expected.equals(request.labelDigest())) {
                throw new UnprocessableException("LABEL_DIGEST_MISMATCH",
                        "标签文本摘要与备案包装文字不一致，登记拒绝");
            }
        }

        LabelPrintRun run = new LabelPrintRun(
                null, request.printRunNo(), request.productCode(),
                filing.filingNo(), filing.versionNo(), filing.filingCategory(),
                request.labelDigest(), bandDigests,
                request.printedCount(), 0, 0,
                PrintRunStatus.ACTIVE, 1, null, null,
                request.requestId(), now, now);
        try {
            labelPrintPort.insertRun(run);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("PRINT_RUN_NO_DUPLICATE",
                    "印次号已存在，同印次号唯一: " + request.printRunNo());
        }

        LabelPrintRun saved = labelPrintPort.findRun(request.printRunNo()).orElseThrow();
        PrintRunResultResponse response = new PrintRunResultResponse(
                200, PrintRunResponse.from(saved), false, now);
        idempotencyPort.complete(request.requestId(), 200, "PRINT_RUN_REGISTERED",
                request.printRunNo(), JsonSupport.toJson(response), now);
        return response;
    }

    /**
     * 儿童印次分档摘要校验：必须冻结各年龄档独立标识摘要，逐档与备案受理冻结的
     * 标识文本一致；缺档、摘要错或夹带未知档一次列全拒绝（422）。
     *
     * @return 校验通过后的分档摘要（按备案分龄档顺序冻结）
     */
    private Map<String, String> validateChildDigests(RegisterPrintRunRequest request,
                                                     FilingRecord filing) {
        List<ChildBandDossier> dossiers = filing.childDossiers() == null
                ? List.of() : filing.childDossiers();
        Map<String, String> expected = new LinkedHashMap<>();
        dossiers.forEach(d -> expected.put(d.bandCode(),
                LabelDigestSupport.bandDigest(d.bandCode(), d.labelText())));

        Map<String, String> provided = request.bandDigests() == null
                ? Map.of() : request.bandDigests();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = provided.get(e.getKey());
            if (got == null) {
                violations.add("缺档：缺少年龄档 " + e.getKey() + " 的独立标识摘要");
            } else if (!got.equals(e.getValue())) {
                violations.add("摘要错：年龄档 " + e.getKey() + " 的标识摘要与备案冻结文本不一致");
            }
        }
        for (String bandCode : provided.keySet()) {
            if (!expected.containsKey(bandCode)) {
                violations.add("未知档：年龄档 " + bandCode + " 不在备案受理冻结的分档集合内");
            }
        }
        if (!violations.isEmpty()) {
            throw new UnprocessableException("CHILD_BAND_DIGEST_VIOLATION",
                    "儿童印次分龄档标识摘要校验失败，须逐档与备案一致", violations);
        }
        String expectedGlobal = LabelDigestSupport.childGlobalDigest(dossiers);
        if (!expectedGlobal.equals(filing.labelDigest())
                || !expectedGlobal.equals(request.labelDigest())) {
            throw new UnprocessableException("LABEL_DIGEST_MISMATCH",
                    "标签文本摘要与备案受理冻结的全局标识摘要不一致，登记拒绝");
        }
        return expected;
    }

    // ============================ 标签领用 ============================

    @Override
    @Transactional
    public LabelIssueResultResponse issue(String printRunNo, IssueLabelsRequest request) {
        Instant now = now();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("printRunNo", printRunNo);
        fingerprintFields.put("batchNo", request.batchNo());
        fingerprintFields.put("quantity", request.quantity());
        fingerprintFields.put("expectedVersion", request.expectedVersion());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<LabelIssueResultResponse> replay = resolveIdempotency(
                request.requestId(), OP_ISSUE, fingerprint,
                LabelIssueResultResponse.class, LabelIssueResultResponse::asReplay);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 锁定印次行：与备案换版/注销事务的禁用更新互斥，重查状态与版本
        LabelPrintRun run = labelPrintPort.findRunForUpdate(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo));
        checkVersion(run, request.expectedVersion());
        if (run.status() != PrintRunStatus.ACTIVE) {
            throw new UnprocessableException("PRINT_RUN_DISABLED",
                    "印次已禁用（" + run.disabledReason() + "），剩余标签不得领用，禁用印次不允许复活");
        }
        // 重查当前备案：新旧修订生效与领用竞争时，旧版生效后不再出领用凭据
        recheckCurrentFiling(run);

        int remaining = run.remainingCount();
        if (remaining < request.quantity()) {
            throw new UnprocessableException("INSUFFICIENT_REMAINING",
                    "未领余额不足：剩余 " + remaining + " 张，本次申请 " + request.quantity() + " 张");
        }

        // 包装批：首次领用登记所属产品；跨产品借用拒绝
        Optional<PackagingBatch> batch = labelPrintPort.findBatch(request.batchNo());
        if (batch.isPresent()) {
            if (!batch.get().productCode().equals(run.productCode())) {
                throw new UnprocessableException("CROSS_PRODUCT_BATCH",
                        "包装批 " + request.batchNo() + " 已登记属于产品 "
                                + batch.get().productCode() + "，跨产品借用拒绝");
            }
        } else {
            labelPrintPort.insertBatch(request.batchNo(), run.productCode(), now);
        }

        int applied = labelPrintPort.applyIssue(printRunNo, request.expectedVersion(),
                request.quantity(), now);
        if (applied != 1) {
            // 行锁下不应发生；兜底按并发冲突处理
            throw new ConflictException("PRINT_RUN_CONCURRENT_MODIFICATION",
                    "印次在领用过程中被并发修改，请重试: " + printRunNo);
        }
        long issueId = labelPrintPort.insertIssue(new LabelIssue(
                null, printRunNo, request.batchNo(), run.productCode(),
                request.quantity(), request.requestId(), now, false, null, null));

        LabelPrintRun updated = labelPrintPort.findRun(printRunNo).orElseThrow();
        LabelIssueResultResponse response = new LabelIssueResultResponse(
                200,
                new LabelIssueResponse(issueId, printRunNo, request.batchNo(), run.productCode(),
                        request.quantity(), now, false, null, null),
                PrintRunResponse.from(updated), false, now);
        idempotencyPort.complete(request.requestId(), 200, "LABELS_ISSUED",
                printRunNo, JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 未领标签销毁 ============================

    @Override
    @Transactional
    public LabelDestructionResultResponse destroy(String printRunNo, DestroyLabelsRequest request) {
        Instant now = now();
        Map<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("requestId", request.requestId());
        fingerprintFields.put("printRunNo", printRunNo);
        fingerprintFields.put("quantity", request.quantity());
        fingerprintFields.put("expectedVersion", request.expectedVersion());
        fingerprintFields.put("reason", request.reason());
        String fingerprint = FingerprintSupport.sha256(fingerprintFields);

        Optional<LabelDestructionResultResponse> replay = resolveIdempotency(
                request.requestId(), OP_DESTROY, fingerprint,
                LabelDestructionResultResponse.class, LabelDestructionResultResponse::asReplay);
        if (replay.isPresent()) {
            return replay.get();
        }

        LabelPrintRun run = labelPrintPort.findRunForUpdate(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo));
        checkVersion(run, request.expectedVersion());
        // 仅未领部分可销毁：已领部分不能销毁退回余额；禁用印次的未领剩余允许销毁（不允许复活）
        int remaining = run.remainingCount();
        if (remaining < request.quantity()) {
            throw new UnprocessableException("INSUFFICIENT_REMAINING",
                    "未领余额不足，已领部分不能销毁退回余额：剩余 " + remaining
                            + " 张，本次申请销毁 " + request.quantity() + " 张");
        }

        int applied = labelPrintPort.applyDestruction(printRunNo, request.expectedVersion(),
                request.quantity(), now);
        if (applied != 1) {
            throw new ConflictException("PRINT_RUN_CONCURRENT_MODIFICATION",
                    "印次在销毁登记过程中被并发修改，请重试: " + printRunNo);
        }
        long destructionId = labelPrintPort.insertDestruction(new LabelDestruction(
                null, printRunNo, request.quantity(), request.reason(),
                request.requestId(), now));

        LabelPrintRun updated = labelPrintPort.findRun(printRunNo).orElseThrow();
        LabelDestructionResultResponse response = new LabelDestructionResultResponse(
                200,
                new LabelDestructionResponse(destructionId, printRunNo, request.quantity(),
                        request.reason(), now),
                PrintRunResponse.from(updated), false, now);
        idempotencyPort.complete(request.requestId(), 200, "LABELS_DESTROYED",
                printRunNo, JsonSupport.toJson(response), now);
        return response;
    }

    // ============================ 查询 ============================

    @Override
    @Transactional(readOnly = true)
    public PrintRunResponse getPrintRun(String printRunNo) {
        return PrintRunResponse.from(labelPrintPort.findRun(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabelIssueResponse> listIssues(String printRunNo) {
        ensureRunExists(printRunNo);
        return labelPrintPort.findIssuesByRun(printRunNo).stream()
                .map(LabelIssueResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabelDestructionResponse> listDestructions(String printRunNo) {
        ensureRunExists(printRunNo);
        return labelPrintPort.findDestructionsByRun(printRunNo).stream()
                .map(LabelDestructionResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabelIssueResponse> listBatchIssues(String batchNo) {
        labelPrintPort.findBatch(batchNo)
                .orElseThrow(() -> new NotFoundException("包装批不存在: " + batchNo));
        return labelPrintPort.findIssuesByBatch(batchNo).stream()
                .map(LabelIssueResponse::from).toList();
    }

    // ============================ 辅助方法 ============================

    private void ensureRunExists(String printRunNo) {
        labelPrintPort.findRun(printRunNo)
                .orElseThrow(() -> new NotFoundException("印次不存在: " + printRunNo));
    }

    /** expectedVersion 与当前印次版本不一致时 409。 */
    private void checkVersion(LabelPrintRun run, Integer expectedVersion) {
        if (expectedVersion == null || expectedVersion != run.versionNo()) {
            throw new ConflictException("VERSION_CONFLICT",
                    "expectedVersion=" + expectedVersion + " 与当前印次版本 "
                            + run.versionNo() + " 不一致（409）");
        }
    }

    /**
     * 领用前重查当前有效备案：有效槽必须仍指向印次登记的备案修订，
     * 否则旧版已失效，拒绝继续领用（409）。
     */
    private void recheckCurrentFiling(LabelPrintRun run) {
        ActiveSlot slot = filingPort.findActiveSlot(run.productCode())
                .orElseThrow(() -> new ConflictException("FILING_REVISION_SUPERSEDED",
                        "产品当前无有效备案，旧印次不得继续领用: " + run.printRunNo()));
        if (!slot.filingNo().equals(run.filingNo()) || slot.versionNo() != run.filingVersionNo()) {
            throw new ConflictException("FILING_REVISION_SUPERSEDED",
                    "备案已换版（当前有效 " + slot.filingNo() + "#" + slot.versionNo()
                            + "，印次绑定 " + run.filingNo() + "#" + run.filingVersionNo()
                            + "），旧印次不得继续领用");
        }
        FilingRecord filing = filingPort.findVersion(slot.filingNo(), slot.versionNo())
                .orElseThrow(() -> new IllegalStateException("有效槽指向的备案版本不存在"));
        if (filing.status() != FilingStatus.EFFECTIVE) {
            throw new ConflictException("FILING_REVISION_SUPERSEDED",
                    "印次绑定备案已非生效态（" + filing.status() + "），旧印次不得继续领用");
        }
    }

    /**
     * 幂等判定：同键同参重放首次结果（标记 idempotentReplay），同键异参 409；
     * 首个事务未回填时等待其完成（并发重放）。
     */
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
