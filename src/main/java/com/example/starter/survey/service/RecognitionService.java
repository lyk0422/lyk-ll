package com.example.starter.survey.service;

import com.example.starter.survey.data.ExternalReportRepository;
import com.example.starter.survey.data.RecognitionBindingRepository;
import com.example.starter.survey.data.RecognitionRiskRepository;
import com.example.starter.survey.data.RecognitionRuleRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.ExternalReport;
import com.example.starter.survey.domain.ExternalReportItem;
import com.example.starter.survey.domain.ItemResult;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.RecognitionItemMapping;
import com.example.starter.survey.domain.RecognitionRuleStatus;
import com.example.starter.survey.domain.RecognitionRuleVersion;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.support.RecognitionConstants;
import com.example.starter.survey.web.BadRequestException;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.BindRecognitionRequest;
import com.example.starter.survey.web.dto.BindingResponse;
import com.example.starter.survey.web.dto.ImportReportRequest;
import com.example.starter.survey.web.dto.PublishRuleRequest;
import com.example.starter.survey.web.dto.ReportItemInput;
import com.example.starter.survey.web.dto.ReportResponse;
import com.example.starter.survey.web.dto.RuleMappingInput;
import com.example.starter.survey.web.dto.RuleVersionResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 船旗国检查报告互认（合成规则）：
 * 规则版本发布、外部报告导入/更正（修订链）、互认绑定申请。
 * 互认只替代映射项目的完成证明；所有写操作经 {@link IdempotentExecutor} 同事务完成。
 */
@Service
public class RecognitionService {

    private final RecognitionRuleRepository ruleRepository;
    private final ExternalReportRepository reportRepository;
    private final RecognitionBindingRepository bindingRepository;
    private final RecognitionRiskRepository riskRepository;
    private final ShipRepository shipRepository;
    private final SurveyRepository surveyRepository;
    private final IdempotentExecutor executor;

    public RecognitionService(RecognitionRuleRepository ruleRepository,
                              ExternalReportRepository reportRepository,
                              RecognitionBindingRepository bindingRepository,
                              RecognitionRiskRepository riskRepository,
                              ShipRepository shipRepository,
                              SurveyRepository surveyRepository,
                              IdempotentExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.reportRepository = reportRepository;
        this.bindingRepository = bindingRepository;
        this.riskRepository = riskRepository;
        this.shipRepository = shipRepository;
        this.surveyRepository = surveyRepository;
        this.executor = executor;
    }

    // ---- 规则版本 ----

    /**
     * 发布合成互认规则新版本（幂等）。
     * expectedVersion 必须等于当前最新版本号（无版本时为 0），否则 409；成功后版本 +1。
     */
    public ApiResult<RuleVersionResponse> publishRule(PublishRuleRequest request) {
        validateDistinct("flagCodes", request.flagCodes());
        Set<String> duplicateItems = new HashSet<>();
        Set<String> seenItems = new HashSet<>();
        for (RuleMappingInput m : request.mappings()) {
            if (!seenItems.add(m.flagItemCode())) {
                duplicateItems.add(m.flagItemCode());
            }
        }
        if (!duplicateItems.isEmpty()) {
            throw new BadRequestException("DUPLICATE_MAPPING_ITEM",
                    "映射中的船旗国检查项目代码重复: " + String.join(",", duplicateItems));
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("expectedVersion", request.expectedVersion());
        fields.put("maxEvidenceValidDays", request.maxEvidenceValidDays());
        fields.put("flagCodes", request.flagCodes().stream().sorted().toList());
        fields.put("mappings", request.mappings().stream()
                .sorted(java.util.Comparator.comparing(RuleMappingInput::flagItemCode))
                .map(m -> Map.of("flagItemCode", m.flagItemCode(),
                        "surveyType", m.surveyType().name(),
                        "classItemCode", m.classItemCode()))
                .toList());
        fields.put("note", request.note() == null ? "" : request.note());
        String fp = Fingerprint.of(request.requestId(), "PUBLISH_RECOGNITION_RULE", fields);

        return executor.run(request.requestId(), fp, RuleVersionResponse.class, () -> {
            int currentMax = ruleRepository.nextVersion() - 1;
            if (request.expectedVersion() != currentMax) {
                throw new ConflictException("RULE_VERSION_CONFLICT",
                        "规则版本已变化：expectedVersion=" + request.expectedVersion()
                                + "，当前最新版本=" + currentMax);
            }
            int newVersion = currentMax + 1;
            List<RecognitionItemMapping> mappings = request.mappings().stream()
                    .map(m -> new RecognitionItemMapping(null, null,
                            m.flagItemCode(), m.surveyType(), m.classItemCode()))
                    .toList();
            try {
                RecognitionRuleVersion created = ruleRepository.publishVersion(newVersion,
                        request.maxEvidenceValidDays(), request.note(), executor.now(),
                        request.flagCodes().stream().distinct().toList(), mappings);
                return toRuleResponse(created);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发发布：uq_rec_rule_version 保证同一版本号只落一个，落败方 409。
                throw new ConflictException("RULE_VERSION_CONFLICT",
                        "并发发布规则版本冲突：版本 " + newVersion + " 已由另一请求创建");
            }
        });
    }

    /** 列出全部规则版本（含船旗与映射明细）。 */
    public List<RuleVersionResponse> listRules() {
        return ruleRepository.findAllVersions().stream().map(this::toRuleResponse).toList();
    }

    /** 按版本号查询规则版本（不存在 404）。 */
    public RuleVersionResponse getRule(int version) {
        RecognitionRuleVersion rule = ruleRepository.findByVersion(version)
                .orElseThrow(() -> new NotFoundException("RULE_VERSION_NOT_FOUND",
                        "互认规则版本不存在: version=" + version));
        return toRuleResponse(rule);
    }

    private RuleVersionResponse toRuleResponse(RecognitionRuleVersion rule) {
        List<String> flagCodes = ruleRepository.findFlagCodes(rule.id());
        List<RuleVersionResponse.RuleMappingView> mappings = ruleRepository
                .findMappings(rule.id()).stream()
                .map(m -> new RuleVersionResponse.RuleMappingView(
                        m.flagItemCode(), m.surveyType().name(), m.classItemCode()))
                .toList();
        return RuleVersionResponse.of(rule, flagCodes, mappings);
    }

    // ---- 外部报告导入 / 更正 ----

    /**
     * 导入外部报告或对同编号报告更正（幂等）。
     * 首次导入 expectedRevision=0；更正须等于当前修订号，内容相同不新增修订，内容不同生成新修订。
     */
    public ApiResult<ReportResponse> importReport(ImportReportRequest request) {
        validateDistinctItems(request.items());

        String contentFp = contentFingerprint(request.reportNo(), request.shipImo(),
                request.flagCode(), request.completedAt(), request.signatureMarker(),
                request.items().stream().map(this::toItemView).toList());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", request.reportNo());
        fields.put("expectedRevision", request.expectedRevision());
        fields.put("shipImo", request.shipImo());
        fields.put("flagCode", request.flagCode());
        fields.put("completedAt", request.completedAt().toString());
        fields.put("signatureMarker", request.signatureMarker());
        fields.put("items", request.items().stream()
                .sorted(java.util.Comparator.comparing(ReportItemInput::itemCode))
                .map(i -> Map.of("itemCode", i.itemCode(), "result", i.result().name(),
                        "note", i.note() == null ? "" : i.note()))
                .toList());
        fields.put("contentFingerprint", contentFp);
        String fp = Fingerprint.of(request.requestId(), "IMPORT_EXTERNAL_REPORT", fields);

        return executor.run(request.requestId(), fp, ReportResponse.class, () -> {
            if (!RecognitionConstants.SYNTHETIC_SIGNATURE_MARKER
                    .equals(request.signatureMarker())) {
                throw new BusinessRuleException("UNRECOGNIZED_SIGNATURE_MARKER",
                        "只接受合成签认标记 " + RecognitionConstants.SYNTHETIC_SIGNATURE_MARKER
                                + "，不访问真实机构");
            }
            // 行锁当前最新修订，与并发更正、并发绑定、并发发证互斥。
            ExternalReport current = reportRepository
                    .findCurrentByReportNoForUpdate(request.reportNo()).orElse(null);
            if (current == null) {
                if (request.expectedRevision() != 0) {
                    throw new NotFoundException("REPORT_NOT_FOUND",
                            "报告不存在，无法在修订号 " + request.expectedRevision()
                                    + " 上更正: " + request.reportNo());
                }
                ExternalReport created = insertRevision(request, 1, contentFp);
                return toReportResponse(created);
            }
            if (request.expectedRevision() != current.revisionNo()) {
                throw new ConflictException("REPORT_REVISION_CONFLICT",
                        "报告修订已变化：expectedRevision=" + request.expectedRevision()
                                + "，当前修订号=" + current.revisionNo());
            }
            String currentContentFp = storedContentFingerprint(current);
            if (contentFp.equals(currentContentFp)) {
                // 同参重复报告不新增修订，直接返回当前修订。
                return toReportResponse(current);
            }
            // 异内容更正：新修订落库，旧修订原子置 SUPERSEDED。
            ExternalReport revised = insertRevision(request, current.revisionNo() + 1, contentFp);
            reportRepository.markSuperseded(current.id(), revised.id());
            handleCorrectionImpact(current, revised);
            return toReportResponse(revised);
        });
    }

    private ExternalReport insertRevision(ImportReportRequest request, int revisionNo,
                                          String contentFp) {
        ExternalReport created;
        try {
            created = reportRepository.insertRevision(
                    request.reportNo(), revisionNo, request.shipImo(), request.flagCode(),
                    request.completedAt(), request.signatureMarker(),
                    request.requestId(), contentFp, executor.now());
        } catch (ExternalReportRepository.RevisionConflict e) {
            // 并发首导/并发更正：唯一索引保证同一修订号只落一行，落败方 409。
            throw new ConflictException("REPORT_REVISION_CONFLICT",
                    "并发冲突：报告 " + request.reportNo() + " 修订号 " + revisionNo
                            + " 已由另一请求创建");
        }
        for (ReportItemInput item : request.items()) {
            reportRepository.insertItem(created.id(), item.itemCode(), item.result(),
                    item.note());
        }
        return reportRepository.findById(created.id()).orElseThrow();
    }

    /**
     * 更正影响（与新修订落库同事务）：
     * 引用旧修订的有效绑定——未签发检验原子失效；已签发检验历史证据不改，另记风险。
     */
    private void handleCorrectionImpact(ExternalReport oldRevision, ExternalReport newRevision) {
        List<RecognitionBinding> activeBindings =
                bindingRepository.findActiveByReportForUpdate(oldRevision.id());
        Instant now = executor.now();
        for (RecognitionBinding binding : activeBindings) {
            Survey survey = surveyRepository.findById(binding.surveyId()).orElse(null);
            if (survey != null && survey.status() == SurveyStatus.CERTIFIED) {
                riskRepository.insert(oldRevision.reportNo(), oldRevision.revisionNo(),
                        newRevision.revisionNo(), survey.id(),
                        RecognitionConstants.REASON_CERTIFIED_HISTORY_REVISED, now);
            } else {
                bindingRepository.invalidate(binding.id(),
                        RecognitionConstants.REASON_REPORT_REVISED, now);
            }
        }
    }

    // ---- 互认绑定 ----

    /**
     * 申请互认绑定（幂等）。引用报告、当前本船未签发检验与规则版本；
     * 跨船、未知映射、证据缺项/不合格、超出最长有效天数一律拒绝。
     */
    public ApiResult<BindingResponse> bind(BindRecognitionRequest request) {
        Survey survey = surveyRepository.findById(request.surveyId())
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + request.surveyId()));
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("surveyId", request.surveyId());
        fields.put("reportNo", request.reportNo());
        fields.put("ruleVersion", request.ruleVersion());
        fields.put("flagItemCode", request.flagItemCode());
        String fp = Fingerprint.of(request.requestId(), "BIND_RECOGNITION", fields);

        return executor.run(request.requestId(), fp, BindingResponse.class, () -> {
            if (survey.status() == SurveyStatus.CERTIFIED) {
                throw new BusinessRuleException("SURVEY_ALREADY_CERTIFIED",
                        "检验已发证，互认只能绑定到未签发检验: surveyId=" + survey.id());
            }
            RecognitionRuleVersion rule = ruleRepository.findByVersion(request.ruleVersion())
                    .orElseThrow(() -> new NotFoundException("RULE_VERSION_NOT_FOUND",
                            "互认规则版本不存在: version=" + request.ruleVersion()));
            if (rule.status() != RecognitionRuleStatus.ACTIVE) {
                throw new BusinessRuleException("RULE_VERSION_NOT_ACTIVE",
                        "规则版本已停用，不能用于新互认: version=" + rule.version());
            }

            // 行锁报告当前修订，冻结一致修订，避免与并发更正交错。
            ExternalReport report = reportRepository
                    .findCurrentByReportNoForUpdate(request.reportNo())
                    .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                            "报告不存在: " + request.reportNo()));
            if (!report.shipImo().equals(ship.imo())) {
                throw new BusinessRuleException("REPORT_SHIP_MISMATCH",
                        "报告船舶 " + report.shipImo() + " 与本船 " + ship.imo()
                                + " 不一致，跨船证据不得互认");
            }
            if (!ruleRepository.isFlagRecognized(rule.id(), report.flagCode())) {
                throw new BusinessRuleException("FLAG_NOT_RECOGNIZED",
                        "规则版本 " + rule.version() + " 未承认船旗: " + report.flagCode());
            }
            RecognitionItemMapping mapping = ruleRepository
                    .findMapping(rule.id(), request.flagItemCode())
                    .orElseThrow(() -> new BusinessRuleException("ITEM_MAPPING_NOT_FOUND",
                            "规则版本 " + rule.version() + " 中不存在检查项目映射: "
                                    + request.flagItemCode()));
            if (mapping.surveyType() != survey.type()) {
                throw new BusinessRuleException("ITEM_TYPE_MISMATCH",
                        "检查项目 " + request.flagItemCode() + " 映射到 "
                                + mapping.surveyType() + " 检验，与本检验类型 "
                                + survey.type() + " 不符");
            }
            List<ExternalReportItem> items = reportRepository.findItems(report.id());
            ExternalReportItem target = items.stream()
                    .filter(i -> i.itemCode().equals(request.flagItemCode()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("EVIDENCE_ITEM_MISSING",
                            "证据缺少逐项结论: " + request.flagItemCode()));
            if (target.result() != ItemResult.PASS) {
                throw new BusinessRuleException("EVIDENCE_ITEM_NOT_PASSING",
                        "检查项目 " + request.flagItemCode() + " 结论为 "
                                + target.result() + "，不得当合格项目互认");
            }
            if (items.stream().anyMatch(i -> i.result() == ItemResult.MAJOR_DEFECT)) {
                throw new BusinessRuleException("REPORT_HAS_MAJOR_DEFECT",
                        "报告存在重大缺陷项，整份报告不得当合格项目互认");
            }
            Instant now = executor.now();
            Instant deadline = report.completedAt()
                    .plus(Duration.ofDays(rule.maxEvidenceValidDays()));
            if (now.isAfter(deadline)) {
                throw new BusinessRuleException("EVIDENCE_EXPIRED",
                        "当前时刻 " + now + " 已超过证据有效截止 " + deadline
                                + "（完成时刻 " + report.completedAt() + " + "
                                + rule.maxEvidenceValidDays() + " 天，UTC，恰截止允许）");
            }

            bindingRepository.findActive(survey.id(), mapping.classItemCode())
                    .ifPresent(existing -> {
                        throw new ConflictException("BINDING_ALREADY_EXISTS",
                                "该检验的船级项目 " + mapping.classItemCode()
                                        + " 已存在有效绑定: bindingId=" + existing.id());
                    });
            try {
                RecognitionBinding created = bindingRepository.insert(
                        request.requestId(), survey.id(), report.id(), report.reportNo(),
                        report.revisionNo(), rule.version(), request.flagItemCode(),
                        survey.type(), mapping.classItemCode(), fp, now);
                return BindingResponse.from(created);
            } catch (RecognitionBindingRepository.ActiveBindingConflict e) {
                throw new ConflictException("BINDING_ALREADY_EXISTS",
                        "并发绑定：该检验的船级项目 " + mapping.classItemCode()
                                + " 仅允许一个有效绑定");
            }
        });
    }

    /** 某检验的全部绑定（含已失效）。 */
    public List<BindingResponse> listBindingsBySurvey(long surveyId) {
        surveyRepository.findById(surveyId)
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + surveyId));
        return bindingRepository.findAllBySurvey(surveyId).stream()
                .map(BindingResponse::from).toList();
    }

    /** 查询报告当前最新修订（含逐项结论）；不存在 404。 */
    public ReportResponse currentReport(String reportNo) {
        ExternalReport current = reportRepository.findCurrentByReportNo(reportNo)
                .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                        "报告不存在: " + reportNo));
        return toReportResponse(current);
    }

    /** 查询报告指定修订（含逐项结论）；报告或修订不存在 404。 */
    public ReportResponse reportRevision(String reportNo, int revisionNo) {
        ExternalReport report = reportRepository.findByReportNoAndRevision(reportNo, revisionNo)
                .orElseThrow(() -> new NotFoundException("REPORT_REVISION_NOT_FOUND",
                        "报告修订不存在: " + reportNo + "#" + revisionNo));
        return toReportResponse(report);
    }

    // ---- 证据链查询 ----

    /** 报告证据链：修订链、逐项结论、全部绑定与风险记录；报告不存在 404。 */
    public com.example.starter.survey.web.dto.EvidenceChainResponse evidenceChain(String reportNo) {
        List<ExternalReport> revisions = reportRepository.findRevisions(reportNo);
        if (revisions.isEmpty()) {
            throw new NotFoundException("REPORT_NOT_FOUND", "报告不存在: " + reportNo);
        }
        List<ReportResponse> revisionViews = revisions.stream()
                .map(this::toReportResponse).toList();
        List<BindingResponse> bindingViews = bindingRepository.findAllByReportNo(reportNo)
                .stream().map(BindingResponse::from).toList();
        List<com.example.starter.survey.web.dto.EvidenceChainResponse.RiskView> riskViews =
                riskRepository.findByReportNo(reportNo).stream()
                        .map(com.example.starter.survey.web.dto.EvidenceChainResponse.RiskView::from)
                        .toList();
        return new com.example.starter.survey.web.dto.EvidenceChainResponse(
                reportNo, revisionViews, bindingViews, riskViews);
    }

    // ---- 公共辅助 ----

    /** 组装报告视图（含逐项结论）。 */
    private ReportResponse toReportResponse(ExternalReport report) {
        return ReportResponse.from(report, reportRepository.findItems(report.id()));
    }

    private void validateDistinctItems(List<ReportItemInput> items) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicate = new HashSet<>();
        for (ReportItemInput item : items) {
            if (!seen.add(item.itemCode())) {
                duplicate.add(item.itemCode());
            }
        }
        if (!duplicate.isEmpty()) {
            throw new BadRequestException("DUPLICATE_REPORT_ITEM",
                    "报告逐项结论的项目代码重复: " + String.join(",", duplicate));
        }
    }

    private static void validateDistinct(String field, List<String> values) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicate = new HashSet<>();
        for (String v : values) {
            if (!seen.add(v)) {
                duplicate.add(v);
            }
        }
        if (!duplicate.isEmpty()) {
            throw new BadRequestException("DUPLICATE_" + field.toUpperCase(java.util.Locale.ROOT),
                    field + " 存在重复值: " + String.join(",", duplicate));
        }
    }

    /** 与请求/存储无关的结构化内容指纹：集合按项目代码排序，JSON 编码天然区分分隔符文本。 */
    private static String contentFingerprint(String reportNo, String shipImo, String flagCode,
                                             Instant completedAt, String signatureMarker,
                                             List<Map<String, Object>> items) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reportNo", reportNo);
        content.put("shipImo", shipImo);
        content.put("flagCode", flagCode);
        content.put("completedAt", completedAt.toString());
        content.put("signatureMarker", signatureMarker);
        content.put("items", items);
        return Fingerprint.of("", "EXTERNAL_REPORT_CONTENT", content);
    }

    private String storedContentFingerprint(ExternalReport report) {
        List<Map<String, Object>> items = reportRepository.findItems(report.id()).stream()
                .sorted(java.util.Comparator.comparing(ExternalReportItem::itemCode))
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("itemCode", i.itemCode());
                    m.put("result", i.result().name());
                    m.put("note", i.note() == null ? "" : i.note());
                    return m;
                })
                .toList();
        return contentFingerprint(report.reportNo(), report.shipImo(), report.flagCode(),
                report.completedAt(), report.signatureMarker(), items);
    }

    private Map<String, Object> toItemView(ReportItemInput item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemCode", item.itemCode());
        m.put("result", item.result().name());
        m.put("note", item.note() == null ? "" : item.note());
        return m;
    }
}
