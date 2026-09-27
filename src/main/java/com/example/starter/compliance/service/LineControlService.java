package com.example.starter.compliance.service;

import com.example.starter.compliance.ApiException;
import com.example.starter.compliance.repo.ClearanceCertificateRow;
import com.example.starter.compliance.repo.ComplianceRepository;
import com.example.starter.compliance.repo.FilingRow;
import com.example.starter.compliance.repo.FormulaAllergenRow;
import com.example.starter.compliance.repo.FormulaRow;
import com.example.starter.compliance.repo.LineControlRepository;
import com.example.starter.compliance.repo.ProductionLineRow;
import com.example.starter.compliance.repo.ProductionRecordRow;
import com.example.starter.compliance.time.ComplianceClock;
import com.example.starter.compliance.web.Requests.ConfirmCertificateRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FormulaAllergenRequest;
import com.example.starter.compliance.web.Requests.RegisterLineRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 清线凭证门禁领域服务：
 *
 * <p>用合成目录为配方版本登记过敏原集合（乐观版本，从 1 开始），为生产线登记最近一次量产版本与
 * 递增占用版本（从 1 开始）。受控量产声明线路与当前占用版本：前批含新批未声明的任一过敏原时，
 * 必须引用覆盖这些过敏原、在确认后 2 小时内有效（恰满 2 小时失效）、线路与占用版本均匹配的双人
 * 确认凭证；其他切换不需凭证。
 *
 * <p>受理时在同一事务内校验备案仍有效、凭证范围与线路版本，再原子写生产记录、推进占用版本与最近
 * 配方、核销并作废旧凭证；任一校验失败整体回滚，不消耗凭证。两笔量产并发时同一线路版本至多一笔
 * 成功（行锁 + (line_code, occupied_version) 唯一约束双重保证）。
 *
 * <p>全部新增写操作以 requestId 幂等：完整参数含版本、集合与精确时刻，同键同参重放首次冻结结果，
 * 异参 409，失败不占键。参数非法 400、对象不存在 404、业务拒绝 422。
 */
@Service
public class LineControlService {

    /** 凭证有效期：UTC 确认后 2 小时，恰满 2 小时失效。 */
    public static final long CERTIFICATE_VALIDITY_MILLIS = 2L * 60L * 60L * 1000L;

    private final LineControlRepository lineRepository;
    private final ComplianceRepository repository;
    private final ComplianceClock clock;
    private final IdempotentExecutor idempotency;
    private final FilingTransitions transitions;

    public LineControlService(LineControlRepository lineRepository,
            ComplianceRepository repository, ComplianceClock clock,
            IdempotentExecutor idempotency, FilingTransitions transitions) {
        this.lineRepository = lineRepository;
        this.repository = repository;
        this.clock = clock;
        this.idempotency = idempotency;
        this.transitions = transitions;
    }

    // ---------------- 配方版本过敏原登记 ----------------

    @Transactional
    public ServiceResult<LineControlResults.FormulaAllerView> registerFormulaAllergens(
            FormulaAllergenRequest request) {
        return idempotency.execute(request.requestId(), request,
                LineControlResults.FormulaAllerView.class, () -> {
                    String productCode = requireText(request.productCode(), "产品编码不能为空");
                    if (request.formulaVersion() == null || request.formulaVersion() < 1) {
                        throw invalid("配方版本号必须为正整数");
                    }
                    if (request.expectedVersion() == null || request.expectedVersion() < 0) {
                        throw invalid("expectedVersion 不能为空且不能为负（新增传 0）");
                    }
                    if (request.registeredAt() == null || request.registeredAt() < 0) {
                        throw invalid("registeredAt 精确时刻不能为空");
                    }
                    List<String> allergens = normalizeAllergens(request.allergens());

                    long now = clock.nowMillis();
                    var existing = lineRepository.findFormulaAllergen(productCode,
                            request.formulaVersion());
                    if (request.expectedVersion() == 0) {
                        if (existing.isPresent()) {
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_VERSION_EXISTS",
                                    "该配方版本已登记过敏原集合，修改须携带当前 expectedVersion");
                        }
                        try {
                            lineRepository.insertFormulaAllergen(productCode,
                                    request.formulaVersion(), allergens, request.registeredAt(), now);
                        } catch (DuplicateKeyException e) {
                            // 并发首次登记：唯一 (product_code, formula_version) 冲突，仅一方成功。
                            throw new ApiException(HttpStatus.CONFLICT, "ALLERGEN_VERSION_EXISTS",
                                    "该配方版本过敏原登记已存在");
                        }
                    } else {
                        FormulaAllergenRow row = existing.orElseThrow(() ->
                                notFound("FORMULA_ALLERGEN_NOT_FOUND",
                                        "配方版本过敏原登记不存在：" + productCode + "#"
                                                + request.formulaVersion()));
                        if (row.version() != request.expectedVersion()) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "OPTIMISTIC_VERSION_CONFLICT",
                                    "过敏原登记版本已变化：expectedVersion="
                                            + request.expectedVersion() + "，当前版本=" + row.version());
                        }
                        if (!lineRepository.updateFormulaAllergen(row.id(), row.version(), allergens,
                                request.registeredAt(), now)) {
                            // 并发修改：乐观版本条件失配，整事务回滚，仅一方提交。
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "OPTIMISTIC_VERSION_CONFLICT", "过敏原登记并发修改冲突");
                        }
                    }
                    FormulaAllergenRow saved = lineRepository.findFormulaAllergen(productCode,
                            request.formulaVersion()).orElseThrow();
                    return new IdempotentExecutor.Outcome<>(buildAllerView(saved, false),
                            HttpStatus.OK.value());
                });
    }

    // ---------------- 生产线登记 ----------------

    @Transactional
    public ServiceResult<LineControlResults.LineView> registerLine(RegisterLineRequest request) {
        return idempotency.execute(request.requestId(), request,
                LineControlResults.LineView.class, () -> {
                    String lineCode = requireText(request.lineCode(), "生产线编码不能为空");
                    requireText(request.lineName(), "生产线名称不能为空");
                    if (request.registeredAt() == null || request.registeredAt() < 0) {
                        throw invalid("registeredAt 精确时刻不能为空");
                    }
                    boolean hasProduct = request.lastProductCode() != null
                            && !request.lastProductCode().isBlank();
                    boolean hasVersion = request.lastFormulaVersion() != null;
                    if (hasProduct != hasVersion) {
                        throw invalid("最近量产产品与版本必须同时提供或同时留空");
                    }
                    if (hasVersion && request.lastFormulaVersion() < 1) {
                        throw invalid("最近量产配方版本号必须为正整数");
                    }
                    if (lineRepository.findLine(lineCode).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_EXISTS",
                                "生产线已登记：" + lineCode);
                    }
                    if (hasProduct
                            && lineRepository.findFormulaAllergen(request.lastProductCode(),
                                    request.lastFormulaVersion()).isEmpty()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "ALLERGEN_REGISTRATION_REQUIRED",
                                "最近量产配方版本尚未登记过敏原集合，无法判定遗留过敏原："
                                        + request.lastProductCode() + "#"
                                        + request.lastFormulaVersion());
                    }
                    try {
                        lineRepository.insertLine(lineCode, request.lineName(),
                                request.registeredAt());
                    } catch (DuplicateKeyException e) {
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_EXISTS",
                                "生产线已登记：" + lineCode);
                    }
                    if (hasProduct) {
                        // 合成登记最近一次量产版本：占用版本固定从 1 开始，最近量产版本直接落列。
                        lineRepository.updateLineLastProduction(lineCode,
                                request.lastProductCode(), request.lastFormulaVersion());
                    }
                    ProductionLineRow row = lineRepository.findLine(lineCode).orElseThrow();
                    return new IdempotentExecutor.Outcome<>(buildLineView(row, false),
                            HttpStatus.OK.value());
                });
    }

    // ---------------- 清线凭证双人确认 ----------------

    @Transactional
    public ServiceResult<LineControlResults.CertificateView> confirmCertificate(
            ConfirmCertificateRequest request) {
        return idempotency.execute(request.requestId(), request,
                LineControlResults.CertificateView.class, () -> {
                    String certificateNo =
                            requireText(request.certificateNo(), "凭证编号不能为空");
                    String lineCode = requireText(request.lineCode(), "生产线编码不能为空");
                    List<String> allergens = normalizeAllergens(request.allergens());
                    if (allergens.isEmpty()) {
                        throw invalid("清线凭证覆盖的过敏原集合不能为空");
                    }
                    String operator = requireText(request.operatorCode(), "操作人不能为空");
                    String reviewer = requireText(request.reviewerCode(), "复核人不能为空");
                    if (operator.equals(reviewer)) {
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "OPERATOR_REVIEWER_SAME",
                                "清线凭证必须经操作人、复核人两名不同人员共同确认");
                    }
                    if (request.clearedAt() == null || request.clearedAt() < 0) {
                        throw invalid("clearedAt 清线时刻不能为空");
                    }
                    if (request.confirmedAt() == null || request.confirmedAt() < 0) {
                        throw invalid("confirmedAt 确认时刻不能为空");
                    }
                    if (request.confirmedAt() < request.clearedAt()) {
                        throw invalid("确认时刻不能早于清线时刻");
                    }
                    if (lineRepository.findCertificateByNo(certificateNo).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_NO_EXISTS",
                                "凭证编号已存在：" + certificateNo);
                    }
                    // 锁线路固定当时占用版本：清线时刻与确认之间若有量产提交，凭证将固定到新占用版本，
                    // 旧版本缺口不再可能被该凭证覆盖。
                    ProductionLineRow line = lineRepository.findLineForUpdate(lineCode)
                            .orElseThrow(() -> notFound("LINE_NOT_FOUND",
                                    "生产线不存在：" + lineCode));
                    long id;
                    try {
                        id = lineRepository.insertCertificate(certificateNo, lineCode, allergens,
                                operator, reviewer, request.clearedAt(), line.occupiedVersion(),
                                request.confirmedAt());
                    } catch (DuplicateKeyException e) {
                        throw new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_NO_EXISTS",
                                "凭证编号已存在：" + certificateNo);
                    }
                    ClearanceCertificateRow saved =
                            lineRepository.findCertificateById(id).orElseThrow();
                    return new IdempotentExecutor.Outcome<>(buildCertificateView(saved, false),
                            HttpStatus.OK.value());
                });
    }

    // ---------------- 受控量产 ----------------

    @Transactional
    public ServiceResult<LineControlResults.ControlledProductionView> controlledProduction(
            ControlledProductionRequest request) {
        return idempotency.execute(request.requestId(), request,
                LineControlResults.ControlledProductionView.class, () -> {
                    if (request.filingId() == null) {
                        throw invalid("filingId 不能为空");
                    }
                    String lineCode = requireText(request.lineCode(), "生产线编码不能为空");
                    if (request.occupiedVersion() == null || request.occupiedVersion() < 1) {
                        throw invalid("occupiedVersion 必须为正整数");
                    }
                    if (request.batchNo() == null || request.batchNo().isBlank()) {
                        throw invalid("批次号不能为空");
                    }
                    if (request.quantityKg() == null || request.quantityKg().signum() < 0) {
                        throw invalid("量产数量必须为非负数");
                    }
                    if (request.reportedAt() == null || request.reportedAt() < 0) {
                        throw invalid("reportedAt 精确时刻不能为空");
                    }

                    // 先锁线路再锁备案：同线路并发量产按唯一提交顺序串行；
                    // 暂停/注销与量产竞态时，后提交者基于行锁内最新状态裁决，历史记录不被改写。
                    ProductionLineRow line = lineRepository.findLineForUpdate(lineCode)
                            .orElseThrow(() -> notFound("LINE_NOT_FOUND",
                                    "生产线不存在：" + lineCode));
                    FilingRow filing = repository.findFilingByIdForUpdate(request.filingId())
                            .orElseThrow(() -> notFound("FILING_NOT_FOUND",
                                    "备案不存在：" + request.filingId()));
                    rejectIfFilingNotEffective(filing);

                    if (line.occupiedVersion() != request.occupiedVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_VERSION_CONFLICT",
                                "线路占用版本不匹配：声明 " + request.occupiedVersion()
                                        + "，当前 " + line.occupiedVersion());
                    }

                    FormulaRow formula = repository.findFormulaById(filing.formulaId())
                            .orElseThrow(() -> notFound("FORMULA_NOT_FOUND",
                                    "备案对应配方不存在：" + filing.formulaId()));
                    List<String> newAllergens = requireRegisteredAllergens(formula.productCode(),
                            formula.version(), "NEW_BATCH_ALLERGEN_NOT_REGISTERED");

                    // 前批遗留过敏原 = 最近量产版本过敏原集合 - 新批声明集合。
                    Set<String> leftover = new TreeSet<>();
                    if (line.lastProductCode() != null) {
                        List<String> previousAllergens = requireRegisteredAllergens(
                                line.lastProductCode(), line.lastFormulaVersion(),
                                "PREVIOUS_BATCH_ALLERGEN_NOT_REGISTERED");
                        leftover.addAll(previousAllergens);
                        leftover.removeAll(newAllergens);
                    }

                    Long usedCertificateId = null;
                    if (!leftover.isEmpty()) {
                        // 前批含新批未声明过敏原：必须引用覆盖全部遗留过敏原的有效凭证。
                        ClearanceCertificateRow certificate = requireCertificate(
                                request.certificateId(), line, request.occupiedVersion(),
                                request.reportedAt(), leftover);
                        usedCertificateId = certificate.id();
                    }

                    long recordId;
                    try {
                        recordId = repository.insertControlledProductionRecord(filing.id(),
                                request.batchNo(), request.quantityKg(), request.reportedAt(),
                                lineCode, request.occupiedVersion(), usedCertificateId);
                    } catch (DuplicateKeyException e) {
                        // 唯一约束兜底：并发同线路同占用版本仅一笔成功，败者不写任何记录。
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_VERSION_CONFLICT",
                                "同一线路版本已有量产提交，占用版本：" + request.occupiedVersion());
                    }
                    if (!lineRepository.occupyLine(lineCode, request.occupiedVersion(),
                            formula.productCode(), formula.version(), request.reportedAt())) {
                        // 行锁内仍失配只会发生在并发推进场景：整事务回滚，凭证不被核销。
                        throw new ApiException(HttpStatus.CONFLICT, "LINE_VERSION_CONFLICT",
                                "线路占用版本已被并发量产推进：" + lineCode);
                    }
                    if (usedCertificateId != null
                            && !lineRepository.consumeCertificate(usedCertificateId,
                                    request.reportedAt())) {
                        // 凭证在锁内已失效（理论被并发改写）：回滚整笔量产，禁止无凭证带过敏原切换。
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "CERTIFICATE_NOT_VALID", "凭证已失效，不能核销");
                    }
                    // 后续任何生产先提交即作废旧凭证：该线路其余 ACTIVE 凭证一律失效。
                    lineRepository.supersedeActiveCertificates(lineCode, request.reportedAt());

                    return new IdempotentExecutor.Outcome<>(
                            new LineControlResults.ControlledProductionView(recordId, filing.id(),
                                    lineCode, request.occupiedVersion(), request.batchNo(),
                                    request.quantityKg().stripTrailingZeros().toPlainString(),
                                    request.reportedAt(), usedCertificateId, false),
                            HttpStatus.OK.value());
                });
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public LineControlResults.FormulaAllerView getAllergenRegistration(String productCode,
            int formulaVersion) {
        FormulaAllergenRow row = lineRepository.findFormulaAllergen(productCode, formulaVersion)
                .orElseThrow(() -> notFound("FORMULA_ALLERGEN_NOT_FOUND",
                        "配方版本过敏原登记不存在：" + productCode + "#" + formulaVersion));
        return buildAllerView(row, false);
    }

    @Transactional(readOnly = true)
    public LineControlResults.LineView getLine(String lineCode) {
        ProductionLineRow row = lineRepository.findLine(lineCode)
                .orElseThrow(() -> notFound("LINE_NOT_FOUND", "生产线不存在：" + lineCode));
        return buildLineView(row, false);
    }

    @Transactional(readOnly = true)
    public LineControlResults.CertificateView getCertificate(long certificateId) {
        ClearanceCertificateRow row = lineRepository.findCertificateById(certificateId)
                .orElseThrow(() -> notFound("CERTIFICATE_NOT_FOUND",
                        "清线凭证不存在：" + certificateId));
        return buildCertificateView(row, false);
    }

    @Transactional(readOnly = true)
    public LineControlResults.LineProductionView getLineProductions(String lineCode) {
        if (lineRepository.findLine(lineCode).isEmpty()) {
            throw notFound("LINE_NOT_FOUND", "生产线不存在：" + lineCode);
        }
        List<LineControlResults.ControlledProductionView> productions =
                repository.findProductionRecordsByLine(lineCode).stream()
                        .map(this::buildProductionView)
                        .toList();
        return new LineControlResults.LineProductionView(lineCode, productions);
    }

    // ---------------- 内部辅助 ----------------

    /** 备案门禁：注销/暂停/复审观察逾期一律拒绝，受控入口不得绕过。 */
    private void rejectIfFilingNotEffective(FilingRow filing) {
        if ("CANCELLED".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_CANCELLED",
                    "备案已注销，受控量产拒绝");
        }
        if ("SUSPENDED".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_SUSPENDED",
                    "备案已暂停，受控量产拒绝");
        }
        if ("REVIEW_WATCH".equals(filing.status())
                && clock.todayEpochDay() > filing.watchStartDay()
                        + ComplianceService.REVIEW_GRACE_DAYS) {
            // 逾期未复审：沿用独立事务立即暂停并释放有效位，随后量产整体回滚（失败不占幂等键）。
            transitions.suspendOverdueNewTransaction(filing);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_SUSPENDED",
                    "备案复审观察已逾期并自动暂停，受控量产拒绝");
        }
        if (!"ACTIVE".equals(filing.status()) && !"REVIEW_WATCH".equals(filing.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILING_NOT_EFFECTIVE",
                    "备案当前状态不允许量产：" + filing.status());
        }
    }

    private ClearanceCertificateRow requireCertificate(Long certificateId,
            ProductionLineRow line, int occupiedVersion, long reportedAt, Set<String> leftover) {
        if (certificateId == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CLEARANCE_CERTIFICATE_REQUIRED",
                    "前批遗留新批未声明过敏原 " + new ArrayList<>(leftover)
                            + "，必须引用有效清线凭证");
        }
        ClearanceCertificateRow certificate = lineRepository.findCertificateById(certificateId)
                .orElseThrow(() -> notFound("CERTIFICATE_NOT_FOUND",
                        "清线凭证不存在：" + certificateId));
        if (!"ACTIVE".equals(certificate.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_NOT_VALID",
                    "清线凭证已" + ("CONSUMED".equals(certificate.status()) ? "核销" : "作废")
                            + "，不能重复使用");
        }
        if (!certificate.lineCode().equals(line.lineCode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CERTIFICATE_LINE_MISMATCH",
                    "凭证线路不匹配：凭证 " + certificate.lineCode() + "，量产线路 "
                            + line.lineCode());
        }
        if (certificate.occupiedVersion() != occupiedVersion) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CERTIFICATE_VERSION_MISMATCH",
                    "凭证固定占用版本 " + certificate.occupiedVersion() + " 与当前占用版本 "
                            + occupiedVersion + " 不匹配");
        }
        long expiresAt = certificate.confirmedAt() + CERTIFICATE_VALIDITY_MILLIS;
        if (reportedAt < certificate.confirmedAt()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_NOT_VALID",
                    "量产时刻早于凭证确认时刻");
        }
        if (reportedAt >= expiresAt) {
            // 恰满 2 小时失效。
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CERTIFICATE_EXPIRED",
                    "清线凭证已超过确认后 2 小时有效期（恰满 2 小时失效）");
        }
        Set<String> uncovered = new TreeSet<>(leftover);
        uncovered.removeAll(certificate.clearedAllergens());
        if (!uncovered.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CERTIFICATE_COVERAGE_INSUFFICIENT",
                    "凭证未覆盖全部遗留过敏原，缺少：" + new ArrayList<>(uncovered));
        }
        return certificate;
    }

    private List<String> requireRegisteredAllergens(String productCode, Integer formulaVersion,
            String errorCode) {
        FormulaAllergenRow row = lineRepository.findFormulaAllergen(productCode, formulaVersion)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode,
                        "配方版本尚未登记过敏原集合：" + productCode + "#" + formulaVersion));
        return row.allergens();
    }

    /** 规范化过敏原集合：去空白、去重、按编码字典序排列，并校验合成目录存在。 */
    private List<String> normalizeAllergens(List<String> allergens) {
        if (allergens == null) {
            throw invalid("过敏原集合不能为空");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : allergens) {
            if (code == null || code.isBlank()) {
                throw invalid("过敏原编码不能为空");
            }
            String trimmed = code.trim();
            if (!lineRepository.allergenExists(trimmed)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ALLERGEN_NOT_FOUND",
                        "过敏原合成目录中不存在：" + trimmed);
            }
            normalized.add(trimmed);
        }
        return List.copyOf(new TreeSet<>(normalized));
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
        return value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static LineControlResults.FormulaAllerView buildAllerView(FormulaAllergenRow row,
            boolean replayed) {
        return new LineControlResults.FormulaAllerView(row.id(), row.productCode(),
                row.formulaVersion(), List.copyOf(row.allergens()), row.version(),
                row.registeredAt(), replayed);
    }

    private static LineControlResults.LineView buildLineView(ProductionLineRow row,
            boolean replayed) {
        return new LineControlResults.LineView(row.lineCode(), row.lineName(),
                row.lastProductCode(), row.lastFormulaVersion(), row.occupiedVersion(),
                row.updatedAt(), replayed);
    }

    private static LineControlResults.CertificateView buildCertificateView(
            ClearanceCertificateRow row, boolean replayed) {
        return new LineControlResults.CertificateView(row.id(), row.certificateNo(),
                row.lineCode(), List.copyOf(row.clearedAllergens()), row.operatorCode(),
                row.reviewerCode(), row.clearedAt(), row.occupiedVersion(), row.status(),
                row.confirmedAt(), row.confirmedAt() + CERTIFICATE_VALIDITY_MILLIS,
                row.consumedAt(), replayed);
    }

    private LineControlResults.ControlledProductionView buildProductionView(
            ProductionRecordRow row) {
        return new LineControlResults.ControlledProductionView(row.id(), row.filingId(),
                row.lineCode(), row.occupiedVersion(), row.batchNo(),
                row.quantityKg().stripTrailingZeros().toPlainString(), row.reportedAt(),
                row.clearanceCertificateId(), false);
    }
}
