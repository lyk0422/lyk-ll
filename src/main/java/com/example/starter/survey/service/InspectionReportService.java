package com.example.starter.survey.service;

import com.example.starter.survey.data.InspectionReportRepository;
import com.example.starter.survey.data.RecognitionBindingRepository;
import com.example.starter.survey.data.RecognitionRiskRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.InspectionReport;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.BadRequestException;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.BindingResponse;
import com.example.starter.survey.web.dto.CorrectReportRequest;
import com.example.starter.survey.web.dto.EvidenceChainResponse;
import com.example.starter.survey.web.dto.ImportReportRequest;
import com.example.starter.survey.web.dto.ReportItemInput;
import com.example.starter.survey.web.dto.ReportResponse;
import com.example.starter.survey.web.dto.RiskResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部船旗国检查报告服务（仅合成签认，不访问真实机构）。
 * 导入：报告编号全局唯一，同编号同内容重复导入不新增、异内容 409；
 * 更正：行锁当前有效修订，expectedVersion 校验后生成新修订，
 * 同一事务内使未签发检验的旧互认失效、已签发检验的历史互认保留并另记风险。
 */
@Service
public class InspectionReportService {

    /** 合成签认标记：只接受该标记，不访问真实机构。 */
    public static final String SYNTHETIC_ATTESTATION = "SYNTHETIC-ATTEST";

    private final InspectionReportRepository reportRepository;
    private final RecognitionBindingRepository bindingRepository;
    private final RecognitionRiskRepository riskRepository;
    private final ShipRepository shipRepository;
    private final SurveyRepository surveyRepository;
    private final IdempotentExecutor executor;

    public InspectionReportService(InspectionReportRepository reportRepository,
                                   RecognitionBindingRepository bindingRepository,
                                   RecognitionRiskRepository riskRepository,
                                   ShipRepository shipRepository,
                                   SurveyRepository surveyRepository,
                                   IdempotentExecutor executor) {
        this.reportRepository = reportRepository;
        this.bindingRepository = bindingRepository;
        this.riskRepository = riskRepository;
        this.shipRepository = shipRepository;
        this.surveyRepository = surveyRepository;
        this.executor = executor;
    }

    /** 导入外部检查报告（首修订，幂等；同编号同内容不新增，异内容 409）。 */
    public ApiResult<ReportResponse> importReport(ImportReportRequest request) {
        validateAttestation(request.attestation());
        shipRepository.findByImo(request.shipImo())
                .orElseThrow(() -> new NotFoundException("SHIP_NOT_FOUND",
                        "船舶不存在: " + request.shipImo()));
        List<Map<String, String>> items = normalizeItems(request.items());
        String contentFp = contentFingerprint(request.reportNo(), request.shipImo(),
                request.flagCode(), request.completedAt().toString(),
                request.attestation(), items);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", request.reportNo());
        fields.put("shipImo", request.shipImo());
        fields.put("flagCode", request.flagCode());
        fields.put("completedAt", request.completedAt().toString());
        fields.put("attestation", request.attestation());
        fields.put("items", items);
        String fp = Fingerprint.of(request.requestId(), "IMPORT_REPORT", fields);

        return executor.run(request.requestId(), fp, ReportResponse.class, () -> {
            var existing = reportRepository.findActiveByReportNo(request.reportNo());
            if (existing.isPresent()) {
                InspectionReport report = existing.get();
                if (!report.contentFingerprint().equals(contentFp)) {
                    throw new ConflictException("REPORT_CONTENT_CONFLICT",
                            "报告编号已存在且内容不同: " + request.reportNo());
                }
                // 同编号同内容：不新增，直接返回已有首修订。
                return toResponse(report);
            }
            try {
                InspectionReport report = reportRepository.insert(
                        request.requestId(), request.reportNo(), request.shipImo(),
                        request.flagCode(), request.completedAt(), request.attestation(),
                        1, 1, null, contentFp, fp, toRows(items), executor.now());
                reportRepository.activate(report.id());
                return toResponse(report);
            } catch (DuplicateKeyException e) {
                // 并发导入同编号：唯一索引等待对方事务后落败，按已提交内容判定。
                InspectionReport committed = reportRepository
                        .findActiveByReportNo(request.reportNo())
                        .orElseThrow(() -> e);
                if (!committed.contentFingerprint().equals(contentFp)) {
                    throw new ConflictException("REPORT_CONTENT_CONFLICT",
                            "报告编号已存在且内容不同: " + request.reportNo());
                }
                return toResponse(committed);
            }
        });
    }

    /**
     * 报告更正（幂等）：生成新修订，同一事务内
     * 使未签发检验的旧互认失效、已签发检验的历史互认保留并登记风险。
     */
    public ApiResult<ReportResponse> correct(CorrectReportRequest request) {
        validateAttestation(request.attestation());
        List<Map<String, String>> items = normalizeItems(request.items());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", request.reportNo());
        fields.put("expectedVersion", request.expectedVersion());
        fields.put("completedAt", request.completedAt().toString());
        fields.put("attestation", request.attestation());
        fields.put("items", items);
        String fp = Fingerprint.of(request.requestId(), "CORRECT_REPORT", fields);

        return executor.run(request.requestId(), fp, ReportResponse.class, () -> {
            // 行锁当前有效修订：与并发更正、并发发证冻结一致修订。
            InspectionReport current = reportRepository
                    .findActiveByReportNoForUpdate(request.reportNo())
                    .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                            "报告不存在: " + request.reportNo()));
            if (current.version() != request.expectedVersion()) {
                throw new ConflictException("VERSION_CONFLICT",
                        "报告版本已变化：期望 expectedVersion=" + current.version()
                                + "，实际提交 " + request.expectedVersion());
            }
            String contentFp = contentFingerprint(request.reportNo(), current.shipImo(),
                    current.flagCode(), request.completedAt().toString(),
                    request.attestation(), items);
            InspectionReport newRevision = reportRepository.insert(
                    request.requestId(), current.reportNo(), current.shipImo(),
                    current.flagCode(), request.completedAt(), request.attestation(),
                    current.revision() + 1, current.version() + 1, current.id(),
                    contentFp, fp, toRows(items), executor.now());
            // 原子交接：旧修订失效 → 新修订生效（同事务）。
            reportRepository.markSuperseded(current.id());
            reportRepository.activate(newRevision.id());

            // 原子波及处理：未签发检验的旧互认失效；已签发检验的历史证据不改、另记风险。
            for (RecognitionBinding binding : bindingRepository.findActiveByReport(current.id())) {
                Survey survey = surveyRepository.findById(binding.surveyId()).orElseThrow();
                if (survey.status() == SurveyStatus.CERTIFIED) {
                    riskRepository.insert(binding.id(), survey.id(), current.reportNo(),
                            "报告 " + current.reportNo() + " 已更正为修订 " + newRevision.revision()
                                    + "，已签发检验 surveyId=" + survey.id()
                                    + " 的历史互认（修订 " + binding.reportRevision()
                                    + "）保留，需人工复核风险",
                            executor.now());
                } else {
                    bindingRepository.invalidate(binding.id(), executor.now());
                }
            }
            return toResponse(newRevision);
        });
    }

    /** 查询当前有效修订。 */
    public ReportResponse getActive(String reportNo) {
        InspectionReport report = reportRepository.findActiveByReportNo(reportNo)
                .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                        "报告不存在: " + reportNo));
        return toResponse(report);
    }

    /** 报告修订链查询。 */
    public List<ReportResponse> revisions(String reportNo) {
        List<InspectionReport> revisions = reportRepository.findRevisions(reportNo);
        if (revisions.isEmpty()) {
            throw new NotFoundException("REPORT_NOT_FOUND", "报告不存在: " + reportNo);
        }
        return revisions.stream().map(this::toResponse).toList();
    }

    /** 证据链查询：全部修订 + 关联互认绑定 + 风险记录。 */
    public EvidenceChainResponse chain(String reportNo) {
        List<ReportResponse> revisions = revisions(reportNo);
        List<BindingResponse> bindings = bindingRepository.findByReportNo(reportNo)
                .stream().map(BindingResponse::from).toList();
        List<RiskResponse> risks = riskRepository.findByReportNo(reportNo)
                .stream().map(RiskResponse::from).toList();
        return new EvidenceChainResponse(reportNo, revisions, bindings, risks);
    }

    /** 视图转换（含逐项结论）。 */
    ReportResponse toResponse(InspectionReport report) {
        return ReportResponse.from(report, reportRepository.findItems(report.id()));
    }

    private static void validateAttestation(String attestation) {
        if (!SYNTHETIC_ATTESTATION.equals(attestation)) {
            throw new BusinessRuleException("NON_SYNTHETIC_ATTESTATION",
                    "只接受合成签认标记 " + SYNTHETIC_ATTESTATION + "，不访问真实机构");
        }
    }

    /** 逐项结论规范化：重复项目代码视为参数非法；按键排序保证指纹无歧义。 */
    static List<Map<String, String>> normalizeItems(List<ReportItemInput> items) {
        List<String> codes = items.stream().map(i -> i.itemCode().name()).distinct().toList();
        if (codes.size() != items.size()) {
            throw new BadRequestException("DUPLICATE_ITEM", "逐项结论存在重复项目代码");
        }
        return items.stream()
                .map(i -> Map.of("itemCode", i.itemCode().name(),
                        "conclusion", i.conclusion().name()))
                .sorted(java.util.Comparator.comparing(m -> m.get("itemCode")))
                .map(m -> (Map<String, String>) m)
                .toList();
    }

    /** 报告业务内容指纹（不含 requestId）：同编号重复导入判定用。 */
    private static String contentFingerprint(String reportNo, String shipImo, String flagCode,
                                             String completedAt, String attestation,
                                             List<Map<String, String>> items) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", reportNo);
        fields.put("shipImo", shipImo);
        fields.put("flagCode", flagCode);
        fields.put("completedAt", completedAt);
        fields.put("attestation", attestation);
        fields.put("items", items);
        return Fingerprint.of("-", "REPORT_CONTENT", fields);
    }

    private static List<InspectionReportRepository.ReportItemRow> toRows(
            List<Map<String, String>> items) {
        return items.stream()
                .map(m -> new InspectionReportRepository.ReportItemRow(
                        com.example.starter.survey.domain.SurveyItem.valueOf(m.get("itemCode")),
                        com.example.starter.survey.domain.ItemConclusion.valueOf(
                                m.get("conclusion"))))
                .toList();
    }
}
