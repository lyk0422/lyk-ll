package com.example.starter.compliance.service;

import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.repo.CleaningCertificateRow;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.repo.FormulaAllergenRow;
import com.example.starter.compliance.repo.FormulaRow;
import com.example.starter.compliance.repo.LineGateRepository;
import com.example.starter.compliance.repo.ProductionLineRow;
import com.example.starter.compliance.support.JsonSupport;
import com.example.starter.compliance.time.ComplianceClock;
import com.example.starter.compliance.web.Requests.CleaningCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FormulaAllergenRequest;
import com.example.starter.compliance.web.Requests.ProductionLineRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产线过敏原切换清线凭证门禁领域服务。
 *
 * <p>门禁规则：受控量产声明线路与当前占用版本；仅当前批（线路最近量产配方版本）含有
 * 新批未声明的任一过敏原时，必须引用覆盖这些过敏原的有效清线凭证，其余切换不得提供凭证。
 * 凭证由操作人、复核人两名不同人员共同确认，固定线路、清线时刻及当时占用版本；
 * UTC 下确认后 2 小时内有效，恰满 2 小时失效；后续任何生产先提交即作废旧凭证。
 *
 * <p>受理在同一事务内行锁线路与备案，原子写生产记录、推进最近配方与占用版本、核销凭证；
 * 任一步失败整事务回滚，凭证不被消耗。两笔并发量产以 expectedVersion 条件更新 +
 * (line_code, occupancy_version) 唯一约束保证最多一笔使用同一线路版本。
 *
 * <p>所有变更接口以 requestId 幂等，指纹为 requestId 与全部业务参数（含版本、集合、精确时刻）
 * 的规范化结构化 JSON 哈希，合法文本即使含分隔符也可区分。
 */
@Service
public class LineGateService {

    /** 清线凭证有效期：UTC 下确认后 2 小时内有效，恰满 2 小时失效。 */
    public static final long CERTIFICATE_TTL_MILLIS = 2L * 60L * 60L * 1000L;

    private final LineGateRepository gateRepository;
    private final ComplianceRepository repository;
    private final ComplianceClock clock;
    private final IdempotencySupport idempotency;
    private final FilingTransitions transitions;

    public LineGateService(LineGateRepository gateRepository, ComplianceRepository repository,
            ComplianceClock clock, IdempotencySupport idempotency,
            FilingTransitions transitions) {
        this.gateRepository = gateRepository;
        this.repository = repository;
        this.clock = clock;
        this.idempotency = idempotency;
        this.transitions = transitions;
    }

    // ---------------- 配方过敏原登记 ----------------

    @Transactional
    public ServiceResult<Results.FormulaAllergenView> registerFormulaAllergens(
            FormulaAllergenRequest request) {
        return idempotency.execute(request.requestId(), request,
                Results.FormulaAllergenView.class, () -> {
                    FormulaRow formula = requireFormula(request.formulaId());
                    if (request.expectedVersion() == null || request.expectedVersion() < 0) {
                        throw invalid("expectedVersion 必须为非负整数（首次登记传 0）");
                    }
                    Set<String> codes = normalizeAllergenCodes(request.allergenCodes());
                    long now = clock.nowMillis();
                    var existing = gateRepository.findFormulaAllergen(formula.id());
                    if (existing.isEmpty()) {
                        if (request.expectedVersion() != 0) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_VERSION_CONFLICT",
                                    "配方尚未登记过敏原，首次登记 expectedVersion 必须为 0");
                        }
                        gateRepository.insertFormulaAllergen(formula.id(), 1, List.copyOf(codes),
                                request.requestId(), now);
                    } else {
                        if (request.expectedVersion() != existing.get().version()) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_VERSION_CONFLICT",
                                    "过敏原登记版本冲突：expectedVersion=" + request.expectedVersion()
                                            + "，当前版本=" + existing.get().version());
                        }
                        int updated = gateRepository.updateFormulaAllergen(formula.id(),
                                request.expectedVersion(), List.copyOf(codes), request.requestId(),
                                now);
                        if (updated == 0) {
                            // 并发修改：条件更新未命中，整事务回滚，仅一方成功。
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_VERSION_CONFLICT",
                                    "过敏原登记并发版本冲突");
                        }
                    }
                    FormulaAllergenRow saved =
                            gateRepository.findFormulaAllergen(formula.id()).orElseThrow();
                    return new IdempotencySupport.Outcome<>(
                            buildAllergenView(saved, false), HttpStatus.OK.value());
                });
    }

    // ---------------- 生产线登记 ----------------

    @Transactional
    public ServiceResult<Results.ProductionLineView> registerLine(ProductionLineRequest request) {
        return idempotency.execute(request.requestId(), request,
                Results.ProductionLineView.class, () -> {
                    if (request.lineCode() == null || request.lineCode().isBlank()) {
                        throw invalid("生产线编码不能为空");
                    }
                    if (request.lineName() == null || request.lineName().isBlank()) {
                        throw invalid("生产线名称不能为空");
                    }
                    if (gateRepository.findProductionLine(request.lineCode()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_EXISTS",
                                "生产线已登记：" + request.lineCode());
                    }
                    long now = clock.nowMillis();
                    gateRepository.insertProductionLine(request.lineCode(), request.lineName(), now);
                    return new IdempotencySupport.Outcome<>(
                            buildLineView(
                                    gateRepository.findProductionLine(request.lineCode()).orElseThrow()),
                            HttpStatus.OK.value());
                });
    }

    // ---------------- 清线凭证双人确认 ----------------

    @Transactional
    public ServiceResult<Results.CleaningCertificateView> confirmCleaning(
            CleaningCertificateRequest request) {
        return idempotency.execute(request.requestId(), request,
                Results.CleaningCertificateView.class, () -> {
                    if (request.lineCode() == null || request.lineCode().isBlank()) {
                        throw invalid("生产线编码不能为空");
                    }
                    if (request.operatorId() == null || request.operatorId().isBlank()) {
                        throw invalid("操作人工号不能为空");
                    }
                    if (request.reviewerId() == null || request.reviewerId().isBlank()) {
                        throw invalid("复核人工号不能为空");
                    }
                    if (request.cleanedAt() == null) {
                        throw invalid("清线时刻不能为空");
                    }
                    Set<String> codes = normalizeAllergenCodes(request.allergenCodes());
                    if (codes.isEmpty()) {
                        throw invalid("清线凭证至少覆盖一种过敏原");
                    }
                    // 双人共同确认：操作人与复核人必须为两名不同人员（业务规则，422）。
                    if (request.operatorId().equals(request.reviewerId())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "CLEANING_SAME_PERSON", "清线确认的操作人与复核人不能为同一人");
                    }
                    // 行锁固定确认时刻的线路占用版本与最近量产配方。
                    ProductionLineRow line = gateRepository.lockProductionLine(request.lineCode())
                            .orElseThrow(() -> notFound("LINE_NOT_FOUND",
                                    "生产线不存在：" + request.lineCode()));
                    long now = clock.nowMillis();
                    long id = gateRepository.insertCleaningCertificate(line.lineCode(),
                            line.occupancyVersion(), line.lastFormulaId(), request.operatorId(),
                            request.reviewerId(), request.cleanedAt(), List.copyOf(codes),
                            request.requestId(), now);
                    return new IdempotencySupport.Outcome<>(
                            buildCertificateView(
                                    gateRepository.findCertificateById(id).orElseThrow(), false),
                            HttpStatus.OK.value());
                });
    }

    // ---------------- 受控量产受理 ----------------

    @Transactional
    public ServiceResult<Results.ControlledProductionView> acceptControlledProduction(
            ControlledProductionRequest request) {
        return idempotency.execute(request.requestId(), request,
                Results.ControlledProductionView.class, () -> {
                    validateControlledRequest(request);
                    // 先锁线路再锁备案：与暂停/注销/并发量产形成唯一提交顺序，避免死锁。
                    ProductionLineRow line = gateRepository.lockProductionLine(request.lineCode())
                            .orElseThrow(() -> notFound("LINE_NOT_FOUND",
                                    "生产线不存在：" + request.lineCode()));
                    if (request.expectedVersion() != line.occupancyVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_VERSION_CONFLICT",
                                "线路占用版本不匹配：expectedVersion=" + request.expectedVersion()
                                        + "，当前占用版本=" + line.occupancyVersion());
                    }

                    // 先以非锁读做逾期判定：逾期暂停走独立事务（REQUIRES_NEW），
                    // 不能先持有该备案的行锁，否则同一连接自我阻塞。
                    FilingRow filingRead = repository.findFilingById(request.filingId())
                            .orElseThrow(() -> notFound("FILING_NOT_FOUND",
                                    "备案不存在：" + request.filingId()));
                    if ("REVIEW_WATCH".equals(filingRead.status())
                            && clock.todayEpochDay()
                                    > filingRead.watchStartDay() + ComplianceService.REVIEW_GRACE_DAYS) {
                        transitions.suspendOverdueNewTransaction(filingRead);
                        throw new ApiException(HttpStatus.CONFLICT, "FILING_OVERDUE",
                                "备案复审观察已逾期并自动暂停，受控量产拒绝");
                    }
                    // 随后锁定备案并复核状态：与并发暂停/注销形成唯一提交顺序。
                    FilingRow filing = gateRepository.lockFiling(request.filingId())
                            .orElseThrow(() -> notFound("FILING_NOT_FOUND",
                                    "备案不存在：" + request.filingId()));
                    checkFilingEffective(filing);
                    FormulaRow formula = requireFormula(filing.formulaId());
                    if (!"APPROVED".equals(formula.state())) {
                        throw rejected("FILING_FORMULA_NOT_APPROVED",
                                "备案对应配方版本不是批准状态，不能受控量产");
                    }

                    Set<String> newAllergens = formulaAllergenSet(formula.id());
                    Set<String> previousAllergens = line.lastFormulaId() == null
                            ? Set.of() : formulaAllergenSet(line.lastFormulaId());
                    Set<String> removed = new TreeSet<>(previousAllergens);
                    removed.removeAll(newAllergens);

                    Long certificateId = null;
                    CleaningCertificateRow certificate = null;
                    if (removed.isEmpty()) {
                        // 其他切换不需凭证：显式携带凭证同样拒绝，防止受控入口绕过门禁语义。
                        if (request.certificateId() != null) {
                            throw rejected("CERTIFICATE_NOT_REQUIRED",
                                    "本次切换不含任何新批未声明的过敏原，不需要清线凭证");
                        }
                    } else {
                        if (request.certificateId() == null) {
                            throw rejected("CERTIFICATE_REQUIRED",
                                    "前批含新批未声明过敏原 " + removed + "，必须引用有效清线凭证");
                        }
                        CleaningCertificateRow loaded = gateRepository
                                .findCertificateById(request.certificateId())
                                .orElseThrow(() -> notFound("CERTIFICATE_NOT_FOUND",
                                        "清线凭证不存在：" + request.certificateId()));
                        validateCertificate(loaded, line, removed, request.occurredAt());
                        certificate = loaded;
                        certificateId = loaded.id();
                    }

                    long now = clock.nowMillis();
                    // 原子推进：行锁后条件更新仍校验占用版本；(line_code, occupancy_version)
                    // 唯一约束兜底，并发最多一笔使用同一线路版本。
                    int advanced = gateRepository.advanceProductionLine(line.lineCode(),
                            line.occupancyVersion(), formula.id(), now);
                    if (advanced == 0) {
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_VERSION_CONFLICT",
                                "线路占用版本并发冲突，请使用最新 expectedVersion 重试");
                    }
                    try {
                        long recordId = gateRepository.insertLineProductionRecord(line.lineCode(),
                                line.occupancyVersion(), formula.id(), filing.id(), certificateId,
                                request.batchNo(), request.quantityKg(), request.occurredAt(),
                                request.requestId(), now);
                        if (certificate != null) {
                            // 核销仅在仍为 VALID 时生效；失败则整事务回滚，凭证不被消耗。
                            int consumed = gateRepository.consumeCertificate(certificate.id(), now);
                            if (consumed == 0) {
                                throw rejected("CERTIFICATE_NOT_VALID",
                                        "清线凭证已失效，不能核销：" + certificate.id());
                            }
                        }
                        // 后续任何生产先提交：同线其余有效凭证立即作废（即使仍在 2 小时窗口内）；
                        // 本次核销凭证已为 CONSUMED，传 -1 不影响，无需凭证时则作废全部 VALID 凭证。
                        long excludeId = certificateId == null ? -1L : certificateId;
                        gateRepository.supersedeOtherValidCertificates(line.lineCode(),
                                excludeId, now);
                        return new IdempotencySupport.Outcome<>(
                                new Results.ControlledProductionView(recordId, line.lineCode(),
                                        line.occupancyVersion(), formula.id(), filing.id(),
                                        certificateId, request.batchNo(),
                                        request.quantityKg().stripTrailingZeros().toPlainString(),
                                        request.occurredAt(), now, false),
                                HttpStatus.OK.value());
                    } catch (DuplicateKeyException e) {
                        // 同一线路同一占用版本唯一约束兜底：整事务回滚，凭证不核销。
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_VERSION_CONFLICT",
                                "并发受控量产冲突，同一线路版本仅允许一笔量产");
                    }
                });
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public Results.FormulaAllergenView getFormulaAllergens(long formulaId) {
        requireFormula(formulaId);
        FormulaAllergenRow row = gateRepository.findFormulaAllergen(formulaId)
                .orElse(new FormulaAllergenRow(formulaId, 0, "[]", null, null));
        return buildAllergenView(row, false);
    }

    @Transactional(readOnly = true)
    public Results.ProductionLineView getLine(String lineCode) {
        return buildLineView(requireLine(lineCode));
    }

    @Transactional(readOnly = true)
    public Results.CleaningCertificateView getCertificate(long certificateId) {
        return buildCertificateView(requireCertificate(certificateId), false);
    }

    @Transactional(readOnly = true)
    public List<Results.CleaningCertificateView> getLineCertificates(String lineCode) {
        requireLine(lineCode);
        return gateRepository.findCertificatesByLine(lineCode).stream()
                .map(row -> buildCertificateView(row, false)).toList();
    }

    @Transactional(readOnly = true)
    public List<Results.ControlledProductionView> getLineProductions(String lineCode) {
        requireLine(lineCode);
        return gateRepository.findLineProductionRecords(lineCode).stream()
                .map(row -> new Results.ControlledProductionView(row.id(), row.lineCode(),
                        row.occupancyVersion(), row.formulaId(), row.filingId(),
                        row.certificateId(), row.batchNo(),
                        row.quantityKg().stripTrailingZeros().toPlainString(), row.occurredAt(),
                        row.createdAt(), false))
                .toList();
    }

    // ---------------- 内部辅助 ----------------

    private void validateControlledRequest(ControlledProductionRequest request) {
        if (request.lineCode() == null || request.lineCode().isBlank()) {
            throw invalid("生产线编码不能为空");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 1) {
            throw invalid("expectedVersion 必须为正整数");
        }
        if (request.filingId() == null) {
            throw invalid("filingId 不能为空");
        }
        if (request.batchNo() == null || request.batchNo().isBlank()) {
            throw invalid("批次号不能为空");
        }
        if (request.quantityKg() == null || request.quantityKg().signum() < 0) {
            throw invalid("量产数量必须为非负数");
        }
        if (request.occurredAt() == null) {
            throw invalid("生产精确时刻不能为空");
        }
    }

    /**
     * 锁定备案后的有效性复核，语义与旧量产入口保持一致：注销、暂停拒绝；
     * 复审观察逾期在加锁前的非锁读阶段以独立事务暂停（避免同连接行锁与 REQUIRES_NEW 自锁），
     * 此处对残留逾期状态做防御性拒绝。
     */
    private void checkFilingEffective(FilingRow filing) {
        if ("CANCELLED".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_CANCELLED",
                    "备案已注销，受控量产一律拒绝");
        }
        if ("SUSPENDED".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_SUSPENDED",
                    "备案已暂停，受控量产拒绝");
        }
        if ("REVIEW_WATCH".equals(filing.status())
                && clock.todayEpochDay() > filing.watchStartDay() + ComplianceService.REVIEW_GRACE_DAYS) {
            throw new ApiException(HttpStatus.CONFLICT, "FILING_OVERDUE",
                    "备案复审观察已逾期并自动暂停，受控量产拒绝");
        }
        if (!"ACTIVE".equals(filing.status()) && !"REVIEW_WATCH".equals(filing.status())) {
            throw rejected("FILING_NOT_ACTIVE", "备案当前状态不允许受控量产：" + filing.status());
        }
    }

    private void validateCertificate(CleaningCertificateRow certificate, ProductionLineRow line,
            Set<String> requiredAllergens, long occurredAt) {
        if (!certificate.lineCode().equals(line.lineCode())) {
            throw rejected("CERTIFICATE_LINE_MISMATCH",
                    "清线凭证不属于该生产线：" + certificate.lineCode());
        }
        if (!"VALID".equals(certificate.status())) {
            throw rejected("CERTIFICATE_NOT_VALID",
                    "清线凭证已" + ("CONSUMED".equals(certificate.status()) ? "核销" : "作废")
                            + "，不能重复使用");
        }
        if (!certificate.expectedVersion().equals(line.occupancyVersion())) {
            throw rejected("CERTIFICATE_LINE_VERSION_MISMATCH",
                    "清线凭证固定的线路版本为 " + certificate.expectedVersion()
                            + "，当前占用版本为 " + line.occupancyVersion());
        }
        long currentLast = line.lastFormulaId() == null ? -1L : line.lastFormulaId();
        long certificateLast = certificate.occupiedFormulaId() == null
                ? -1L : certificate.occupiedFormulaId();
        if (currentLast != certificateLast) {
            throw rejected("CERTIFICATE_OCCUPANCY_MISMATCH",
                    "清线凭证确认时占用配方与线路当前最近量产配方不一致");
        }
        if (occurredAt < certificate.cleanedAt()) {
            throw rejected("PRODUCTION_BEFORE_CLEANING",
                    "生产时刻早于清线确认时刻，凭证不能覆盖该批次");
        }
        // 有效期 [cleanedAt, cleanedAt + 2h)：恰满 2 小时失效。
        if (occurredAt >= certificate.cleanedAt() + CERTIFICATE_TTL_MILLIS) {
            throw rejected("CERTIFICATE_EXPIRED",
                    "清线凭证在 UTC 确认后 2 小时失效，生产时刻已超出有效窗口");
        }
        Set<String> covered = new TreeSet<>(readCodeList(certificate.allergenCodes()));
        if (!covered.containsAll(requiredAllergens)) {
            Set<String> missing = new TreeSet<>(requiredAllergens);
            missing.removeAll(covered);
            throw rejected("CERTIFICATE_COVERAGE_INSUFFICIENT",
                    "清线凭证未覆盖过敏原：" + missing);
        }
    }

    private Set<String> formulaAllergenSet(long formulaId) {
        return gateRepository.findFormulaAllergen(formulaId)
                .map(row -> Set.copyOf(readCodeList(row.allergenCodes())))
                .orElse(Set.of());
    }

    private Set<String> normalizeAllergenCodes(List<String> codes) {
        if (codes == null) {
            throw invalid("过敏原编码集合不能为空");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw invalid("过敏原编码不能为空");
            }
            if (!gateRepository.allergenExists(code)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ALLERGEN_NOT_FOUND",
                        "过敏原目录中不存在：" + code);
            }
            if (!seen.add(code)) {
                throw invalid("过敏原编码重复：" + code);
            }
        }
        return new TreeSet<>(seen);
    }

    @SuppressWarnings("unchecked")
    private static List<String> readCodeList(String json) {
        List<?> raw = JsonSupport.read(json, List.class);
        return raw.stream().map(String::valueOf).collect(Collectors.toList());
    }

    private FormulaRow requireFormula(Long formulaId) {
        if (formulaId == null) {
            throw invalid("formulaId 不能为空");
        }
        return repository.findFormulaById(formulaId)
                .orElseThrow(() -> notFound("FORMULA_NOT_FOUND", "配方不存在：" + formulaId));
    }

    private ProductionLineRow requireLine(String lineCode) {
        if (lineCode == null || lineCode.isBlank()) {
            throw invalid("生产线编码不能为空");
        }
        return gateRepository.findProductionLine(lineCode)
                .orElseThrow(() -> notFound("LINE_NOT_FOUND", "生产线不存在：" + lineCode));
    }

    private CleaningCertificateRow requireCertificate(long certificateId) {
        return gateRepository.findCertificateById(certificateId)
                .orElseThrow(() -> notFound("CERTIFICATE_NOT_FOUND",
                        "清线凭证不存在：" + certificateId));
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static ApiException rejected(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private Results.FormulaAllergenView buildAllergenView(FormulaAllergenRow row, boolean replayed) {
        List<String> codes = readCodeList(row.allergenCodes() == null ? "[]" : row.allergenCodes())
                .stream().sorted().toList();
        return new Results.FormulaAllergenView(row.formulaId(), row.version(), codes,
                row.updatedAt(), replayed);
    }

    private Results.ProductionLineView buildLineView(ProductionLineRow row) {
        return new Results.ProductionLineView(row.lineCode(), row.lineName(), row.lastFormulaId(),
                row.occupancyVersion(), row.createdAt(), row.updatedAt());
    }

    private Results.CleaningCertificateView buildCertificateView(CleaningCertificateRow row,
            boolean replayed) {
        List<String> codes = readCodeList(row.allergenCodes()).stream().sorted().toList();
        long expiresAt = row.cleanedAt() + CERTIFICATE_TTL_MILLIS;
        long now = clock.nowMillis();
        boolean valid = "VALID".equals(row.status()) && now >= row.cleanedAt()
                && now < expiresAt;
        return new Results.CleaningCertificateView(row.id(), row.lineCode(), row.expectedVersion(),
                row.occupiedFormulaId(), row.operatorId(), row.reviewerId(), row.cleanedAt(),
                expiresAt, codes, row.status(), valid, row.consumedAt(), row.createdAt(), replayed);
    }
}
