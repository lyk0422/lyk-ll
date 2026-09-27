package com.example.starter.survey.service;

import com.example.starter.survey.data.FlagReportRepository;
import com.example.starter.survey.data.FlagRuleRepository;
import com.example.starter.survey.data.RecognitionBindingRepository;
import com.example.starter.survey.data.RecognitionRiskRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.FlagInspectionReport;
import com.example.starter.survey.domain.FlagReportItem;
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
import com.example.starter.survey.web.dto.CorrectFlagReportRequest;
import com.example.starter.survey.web.dto.FlagReportItemInput;
import com.example.starter.survey.web.dto.FlagReportResponse;
import com.example.starter.survey.web.dto.ImportFlagReportRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 外部船旗国检查证据报告：导入、更正与修订查询。
 * 报告编号全局唯一；更正生成新修订，并在同一事务内使未签发检验的旧互认失效、
 * 已签发历史证据保留原样并另记风险。仅接受合成签认标记，不访问真实机构。
 */
@Service
public class FlagReportService {

    /** 唯一接受的合成签认标记。 */
    public static final String SYNTHETIC_SIGNOFF = "SYNTHETIC-SIGNOFF";

    private final FlagReportRepository reportRepository;
    private final FlagRuleRepository ruleRepository;
    private final RecognitionBindingRepository bindingRepository;
    private final RecognitionRiskRepository riskRepository;
    private final ShipRepository shipRepository;
    private final SurveyRepository surveyRepository;
    private final IdempotentExecutor executor;

    public FlagReportService(FlagReportRepository reportRepository,
                             FlagRuleRepository ruleRepository,
                             RecognitionBindingRepository bindingRepository,
                             RecognitionRiskRepository riskRepository,
                             ShipRepository shipRepository,
                             SurveyRepository surveyRepository,
                             IdempotentExecutor executor) {
        this.reportRepository = reportRepository;
        this.ruleRepository = ruleRepository;
        this.bindingRepository = bindingRepository;
        this.riskRepository = riskRepository;
        this.shipRepository = shipRepository;
        this.surveyRepository = surveyRepository;
        this.executor = executor;
    }

    /**
     * 导入外部检查证据报告（幂等）。
     * 同报告编号同内容不新增（返回当前最新修订）；同编号异内容 409。
     */
    public ApiResult<FlagReportResponse> importReport(ImportFlagReportRequest request) {
        List<FlagReportItemInput> items = sortedDistinctItems(request.items());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", request.reportNo());
        fields.put("imo", request.imo());
        fields.put("flagCode", request.flagCode());
        fields.put("completedAt", request.completedAt().toString());
        fields.put("syntheticSignoff", request.syntheticSignoff());
        fields.put("items", itemFingerprints(items));
        String fp = Fingerprint.of(request.requestId(), "IMPORT_FLAG_REPORT", fields);

        return executor.run(request.requestId(), fp, FlagReportResponse.class, () -> {
            shipRepository.findByImo(request.imo())
                    .orElseThrow(() -> new NotFoundException("SHIP_NOT_FOUND",
                            "船舶不存在: " + request.imo()));
            if (ruleRepository.findLatest(request.flagCode()).isEmpty()) {
                throw new BusinessRuleException("UNKNOWN_FLAG_CODE",
                        "船旗代码无互认规则: " + request.flagCode());
            }
            requireSyntheticSignoff(request.syntheticSignoff());

            List<FlagInspectionReport> revisions = reportRepository.findRevisions(request.reportNo());
            if (!revisions.isEmpty()) {
                FlagInspectionReport original = revisions.get(0);
                if (sameContent(original, request, items)) {
                    // 同参重复报告不新增：返回当前最新修订视图。
                    FlagInspectionReport latest = revisions.get(revisions.size() - 1);
                    return FlagReportResponse.from(latest, reportRepository.findItems(latest.id()));
                }
                throw new ConflictException("REPORT_CONTENT_CONFLICT",
                        "报告编号已存在且内容不一致: " + request.reportNo());
            }
            FlagInspectionReport created = reportRepository.insertRevision(request.requestId(),
                    request.reportNo(), 1, request.imo(), request.flagCode(),
                    request.completedAt(), request.syntheticSignoff(), executor.now());
            for (FlagReportItemInput item : items) {
                reportRepository.insertItem(created.id(), item.flagItemCode(), item.conclusion());
            }
            return FlagReportResponse.from(created, reportRepository.findItems(created.id()));
        });
    }

    /**
     * 报告更正（幂等）：生成新修订；同一事务内使未签发检验的旧互认失效，
     * 已签发检验的历史绑定不改动、逐条登记风险。
     */
    public ApiResult<FlagReportResponse> correct(String reportNo, CorrectFlagReportRequest request) {
        List<FlagReportItemInput> items = sortedDistinctItems(request.items());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", reportNo);
        fields.put("expectedVersion", request.expectedVersion());
        fields.put("completedAt", request.completedAt().toString());
        fields.put("syntheticSignoff", request.syntheticSignoff());
        fields.put("items", itemFingerprints(items));
        String fp = Fingerprint.of(request.requestId(), "CORRECT_FLAG_REPORT", fields);

        return executor.run(request.requestId(), fp, FlagReportResponse.class, () -> {
            List<FlagInspectionReport> revisions = reportRepository.findRevisions(reportNo);
            if (revisions.isEmpty()) {
                throw new NotFoundException("REPORT_NOT_FOUND", "报告不存在: " + reportNo);
            }
            FlagInspectionReport latest = revisions.get(revisions.size() - 1);
            if (latest.revision() != request.expectedVersion()) {
                throw new ConflictException("REPORT_REVISION_CONFLICT",
                        "报告修订冲突：当前修订 " + latest.revision()
                                + "，请求 expectedVersion=" + request.expectedVersion());
            }
            requireSyntheticSignoff(request.syntheticSignoff());

            int newRevision = latest.revision() + 1;
            Instant now = executor.now();
            FlagInspectionReport created;
            try {
                created = reportRepository.insertRevision(request.requestId(), reportNo, newRevision,
                        latest.imo(), latest.flagCode(), request.completedAt(),
                        request.syntheticSignoff(), now);
            } catch (DuplicateKeyException e) {
                // 并发更正：唯一索引保证同一修订号只落一行，竞争方落败。
                throw new ConflictException("REPORT_REVISION_CONFLICT",
                        "并发更正冲突：报告 " + reportNo + " 已由另一请求生成修订 " + newRevision);
            }
            for (FlagReportItemInput item : items) {
                reportRepository.insertItem(created.id(), item.flagItemCode(), item.conclusion());
            }

            // 原子失效处理：行锁检验记录，与发证串行化。
            for (RecognitionBinding binding : bindingRepository.findActiveByReportNo(reportNo)) {
                Survey survey = surveyRepository.findByIdForUpdate(binding.surveyId()).orElseThrow();
                if (survey.status() == SurveyStatus.CERTIFIED) {
                    // 已签发历史证据不改，另记风险。
                    riskRepository.insert(binding.id(), reportNo, newRevision,
                            "报告修订 " + newRevision + " 影响已签发检验的历史互认证据",
                            now);
                } else {
                    bindingRepository.invalidate(binding.id(), now);
                }
            }
            return FlagReportResponse.from(created, reportRepository.findItems(created.id()));
        });
    }

    /** 列出报告全部修订（含逐项结论）。 */
    public List<FlagReportResponse> listRevisions(String reportNo) {
        List<FlagInspectionReport> revisions = reportRepository.findRevisions(reportNo);
        if (revisions.isEmpty()) {
            throw new NotFoundException("REPORT_NOT_FOUND", "报告不存在: " + reportNo);
        }
        return revisions.stream()
                .map(r -> FlagReportResponse.from(r, reportRepository.findItems(r.id())))
                .toList();
    }

    /** 仅接受合成签认标记。 */
    private void requireSyntheticSignoff(String syntheticSignoff) {
        if (!SYNTHETIC_SIGNOFF.equals(syntheticSignoff)) {
            throw new BusinessRuleException("SYNTHETIC_SIGNOFF_REQUIRED",
                    "仅接受合成签认标记 " + SYNTHETIC_SIGNOFF + "，不访问真实机构");
        }
    }

    /** 同内容判定：IMO、船旗、完成时刻、签认标记与逐项结论集合完全一致。 */
    private boolean sameContent(FlagInspectionReport original, ImportFlagReportRequest request,
                                List<FlagReportItemInput> items) {
        if (!original.imo().equals(request.imo())
                || !original.flagCode().equals(request.flagCode())
                || !original.completedAt().equals(request.completedAt())
                || !original.syntheticSignoff().equals(request.syntheticSignoff())) {
            return false;
        }
        Map<String, String> existing = reportRepository.findItems(original.id()).stream()
                .collect(Collectors.toMap(FlagReportItem::flagItemCode,
                        i -> i.conclusion().name()));
        Map<String, String> incoming = items.stream()
                .collect(Collectors.toMap(FlagReportItemInput::flagItemCode,
                        i -> i.conclusion().name()));
        return existing.equals(incoming);
    }

    /** 逐项结论按船旗项目排序并校验无重复项目代码。 */
    private List<FlagReportItemInput> sortedDistinctItems(List<FlagReportItemInput> items) {
        Set<String> codes = new HashSet<>();
        for (FlagReportItemInput item : items) {
            if (!codes.add(item.flagItemCode())) {
                throw new BadRequestException("DUPLICATE_FLAG_ITEM",
                        "逐项结论项目重复: " + item.flagItemCode());
            }
        }
        return items.stream()
                .sorted(Comparator.comparing(FlagReportItemInput::flagItemCode))
                .toList();
    }

    /** 逐项结论指纹片段：结构化列表，文本含分隔符也不产生歧义。 */
    private List<Map<String, String>> itemFingerprints(List<FlagReportItemInput> items) {
        return items.stream()
                .map(i -> Map.of("flagItemCode", i.flagItemCode(),
                        "conclusion", i.conclusion().name()))
                .toList();
    }
}
