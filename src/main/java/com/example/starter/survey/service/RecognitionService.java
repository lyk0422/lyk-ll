package com.example.starter.survey.service;

import com.example.starter.survey.data.FlagReportRepository;
import com.example.starter.survey.data.FlagRuleRepository;
import com.example.starter.survey.data.RecognitionBindingRepository;
import com.example.starter.survey.data.RecognitionRiskRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.FlagInspectionReport;
import com.example.starter.survey.domain.FlagItemConclusion;
import com.example.starter.survey.domain.FlagReportItem;
import com.example.starter.survey.domain.FlagRuleItem;
import com.example.starter.survey.domain.FlagRuleVersion;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.BadRequestException;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyRecognitionRequest;
import com.example.starter.survey.web.dto.ApplyRecognitionResponse;
import com.example.starter.survey.web.dto.EvidenceChainResponse;
import com.example.starter.survey.web.dto.FlagReportResponse;
import com.example.starter.survey.web.dto.FlagRuleVersionResponse;
import com.example.starter.survey.web.dto.RecognitionBindingResponse;
import com.example.starter.survey.web.dto.RecognitionRiskResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 限定范围互认：将外部报告（最新修订）的合格项目绑定到本船未签发检验的船级项目。
 * 互认只替代映射项目的完成证明，不跳过年度窗口、重大缺陷整改与证书签发门禁；
 * 报告修订与规则版本在绑定时冻结。
 */
@Service
public class RecognitionService {

    private final RecognitionBindingRepository bindingRepository;
    private final RecognitionRiskRepository riskRepository;
    private final FlagReportRepository reportRepository;
    private final FlagRuleRepository ruleRepository;
    private final SurveyRepository surveyRepository;
    private final ShipRepository shipRepository;
    private final IdempotentExecutor executor;

    public RecognitionService(RecognitionBindingRepository bindingRepository,
                              RecognitionRiskRepository riskRepository,
                              FlagReportRepository reportRepository,
                              FlagRuleRepository ruleRepository,
                              SurveyRepository surveyRepository,
                              ShipRepository shipRepository,
                              IdempotentExecutor executor) {
        this.bindingRepository = bindingRepository;
        this.riskRepository = riskRepository;
        this.reportRepository = reportRepository;
        this.ruleRepository = ruleRepository;
        this.surveyRepository = surveyRepository;
        this.shipRepository = shipRepository;
        this.executor = executor;
    }

    /** 申请互认（幂等；每船级项目仅一个有效绑定）。 */
    public ApiResult<ApplyRecognitionResponse> apply(ApplyRecognitionRequest request) {
        List<String> classItems = sortedDistinctClassItems(request.classItems());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", request.reportNo());
        fields.put("surveyId", request.surveyId());
        fields.put("ruleVersion", request.ruleVersion());
        fields.put("classItems", classItems);
        String fp = Fingerprint.of(request.requestId(), "APPLY_RECOGNITION", fields);

        return executor.run(request.requestId(), fp, ApplyRecognitionResponse.class,
                () -> doApply(request, classItems));
    }

    private ApplyRecognitionResponse doApply(ApplyRecognitionRequest request, List<String> classItems) {
        Survey survey = surveyRepository.findById(request.surveyId())
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + request.surveyId()));
        FlagInspectionReport report = reportRepository.findLatestRevision(request.reportNo())
                .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                        "报告不存在: " + request.reportNo()));
        FlagRuleVersion rule = ruleRepository
                .findByFlagAndVersion(report.flagCode(), request.ruleVersion())
                .orElseThrow(() -> new NotFoundException("RULE_VERSION_NOT_FOUND",
                        "规则版本不存在: flagCode=" + report.flagCode()
                                + ", version=" + request.ruleVersion()));

        if (survey.status() == SurveyStatus.CERTIFIED) {
            throw new BusinessRuleException("SURVEY_ALREADY_CERTIFIED",
                    "检验已签发，不能再申请互认: surveyId=" + survey.id());
        }
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();
        if (!report.imo().equals(ship.imo())) {
            throw new BusinessRuleException("CROSS_SHIP_REPORT",
                    "跨船证据不得互认：报告 IMO=" + report.imo() + "，检验船舶 IMO=" + ship.imo());
        }

        Instant now = executor.now();
        Instant deadline = report.completedAt().plus(rule.maxEvidenceAgeDays(), ChronoUnit.DAYS);
        if (now.isAfter(deadline)) {
            throw new BusinessRuleException("EVIDENCE_EXPIRED",
                    "证据已过期：完成时刻 " + report.completedAt() + " + "
                            + rule.maxEvidenceAgeDays() + " 天，截止 " + deadline + "，当前 " + now);
        }

        List<FlagRuleItem> mappings = ruleRepository.findItems(rule.id());
        List<FlagReportItem> reportItems = reportRepository.findItems(report.id());
        List<RecognitionBindingResponse> bindings = new ArrayList<>();
        for (String classItem : classItems) {
            FlagRuleItem mapping = mappings.stream()
                    .filter(m -> m.classItemCode().equals(classItem))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("UNKNOWN_ITEM_MAPPING",
                            "船级项目无互认映射: " + classItem));
            FlagReportItem evidence = reportItems.stream()
                    .filter(i -> i.flagItemCode().equals(mapping.flagItemCode()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("EVIDENCE_ITEM_MISSING",
                            "报告缺少映射项目的逐项结论: " + mapping.flagItemCode()));
            if (evidence.conclusion() != FlagItemConclusion.SATISFACTORY) {
                throw new BusinessRuleException("EVIDENCE_NOT_QUALIFIED",
                        "项目结论非合格，不得充当互认证据: " + mapping.flagItemCode()
                                + " conclusion=" + evidence.conclusion());
            }
            if (bindingRepository.findActive(survey.id(), classItem).isPresent()) {
                throw new BusinessRuleException("BINDING_ALREADY_ACTIVE",
                        "该船级项目已存在有效互认绑定: " + classItem);
            }
            RecognitionBinding binding;
            try {
                binding = bindingRepository.insert(request.requestId(), survey.id(),
                        report.id(), rule.id(), mapping.flagItemCode(), classItem, now);
            } catch (DuplicateKeyException e) {
                // 并发互认：唯一索引保证同检验同项目仅一个有效绑定，竞争方落败。
                throw new ConflictException("BINDING_RACE_LOST",
                        "并发互认冲突：该检验项目已由另一请求绑定: " + classItem);
            }
            bindings.add(RecognitionBindingResponse.from(binding,
                    report.reportNo(), report.revision(), rule.version()));
        }
        return new ApplyRecognitionResponse(survey.id(), bindings);
    }

    /** 查询检验的互认绑定列表。 */
    public List<RecognitionBindingResponse> listBySurvey(long surveyId) {
        surveyRepository.findById(surveyId)
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + surveyId));
        return bindingRepository.findBySurvey(surveyId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** 证据链查询：绑定 + 冻结报告修订（含逐项结论）+ 冻结规则版本（含映射）+ 风险记录。 */
    public EvidenceChainResponse evidenceChain(long surveyId) {
        surveyRepository.findById(surveyId)
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + surveyId));
        List<EvidenceChainResponse.Entry> entries = bindingRepository.findBySurvey(surveyId).stream()
                .map(binding -> {
                    FlagInspectionReport report = reportRepository.findById(binding.reportId())
                            .orElseThrow();
                    FlagRuleVersion rule = ruleRepository.findById(binding.ruleId()).orElseThrow();
                    return new EvidenceChainResponse.Entry(
                            toResponse(binding),
                            FlagReportResponse.from(report, reportRepository.findItems(report.id())),
                            FlagRuleVersionResponse.from(rule, ruleRepository.findItems(rule.id())));
                })
                .toList();
        List<RecognitionRiskResponse> risks = riskRepository.findBySurvey(surveyId).stream()
                .map(RecognitionRiskResponse::from)
                .toList();
        return new EvidenceChainResponse(surveyId, entries, risks);
    }

    /** 绑定转视图（补充报告编号/修订号与规则版本）。 */
    private RecognitionBindingResponse toResponse(RecognitionBinding binding) {
        FlagInspectionReport report = reportRepository.findById(binding.reportId()).orElseThrow();
        FlagRuleVersion rule = ruleRepository.findById(binding.ruleId()).orElseThrow();
        return RecognitionBindingResponse.from(binding,
                report.reportNo(), report.revision(), rule.version());
    }

    /** 船级项目排序并校验无重复。 */
    private List<String> sortedDistinctClassItems(List<String> classItems) {
        Set<String> seen = new HashSet<>();
        for (String item : classItems) {
            if (!seen.add(item)) {
                throw new BadRequestException("DUPLICATE_CLASS_ITEM",
                        "船级项目重复: " + item);
            }
        }
        return classItems.stream().sorted().toList();
    }
}
