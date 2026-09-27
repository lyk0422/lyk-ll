package com.example.starter.survey.service;

import com.example.starter.survey.data.InspectionReportRepository;
import com.example.starter.survey.data.RecognitionBindingRepository;
import com.example.starter.survey.data.RecognitionRuleRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.InspectionReport;
import com.example.starter.survey.domain.InspectionReportItem;
import com.example.starter.survey.domain.ItemConclusion;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.RecognitionRule;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyItem;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyRecognitionRequest;
import com.example.starter.survey.web.dto.ApplyRecognitionResponse;
import com.example.starter.survey.web.dto.BindingResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 互认申请服务。
 * 互认只在规则指定的项目范围内替代完成证明：引用报告当前有效修订、
 * 本船未签发检验与规则版本；每个项目只一个有效绑定（唯一索引兜底）。
 */
@Service
public class RecognitionService {

    private final RecognitionRuleRepository ruleRepository;
    private final InspectionReportRepository reportRepository;
    private final RecognitionBindingRepository bindingRepository;
    private final SurveyRepository surveyRepository;
    private final ShipRepository shipRepository;
    private final IdempotentExecutor executor;

    public RecognitionService(RecognitionRuleRepository ruleRepository,
                              InspectionReportRepository reportRepository,
                              RecognitionBindingRepository bindingRepository,
                              SurveyRepository surveyRepository,
                              ShipRepository shipRepository,
                              IdempotentExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.reportRepository = reportRepository;
        this.bindingRepository = bindingRepository;
        this.surveyRepository = surveyRepository;
        this.shipRepository = shipRepository;
        this.executor = executor;
    }

    /**
     * 申请互认（幂等）。
     * 拒绝分支：跨船、船旗与规则不符、未知映射、证据缺项、项目不合格（含重大缺陷）、
     * 证据超期（now &gt; 完成时刻+有效天数；恰到截止允许）、检验已签发、项目已有有效绑定。
     */
    public ApiResult<ApplyRecognitionResponse> apply(ApplyRecognitionRequest request) {
        Survey survey = surveyRepository.findById(request.surveyId())
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + request.surveyId()));
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("surveyId", request.surveyId());
        fields.put("reportNo", request.reportNo());
        fields.put("ruleVersion", request.ruleVersion());
        String fp = Fingerprint.of(request.requestId(), "APPLY_RECOGNITION", fields);

        return executor.run(request.requestId(), fp, ApplyRecognitionResponse.class, () -> {
            if (survey.status() == SurveyStatus.CERTIFIED) {
                throw new BusinessRuleException("SURVEY_ALREADY_CERTIFIED",
                        "检验已签发，不能申请互认: surveyId=" + survey.id());
            }
            RecognitionRule rule = ruleRepository.findByVersion(request.ruleVersion())
                    .orElseThrow(() -> new NotFoundException("RULE_NOT_FOUND",
                            "互认规则版本不存在: " + request.ruleVersion()));
            // 行锁报告当前有效修订：与并发更正、并发发证冻结一致修订与规则版本。
            InspectionReport report = reportRepository
                    .findActiveByReportNoForUpdate(request.reportNo())
                    .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                            "报告不存在: " + request.reportNo()));
            if (!report.shipImo().equals(ship.imo())) {
                throw new BusinessRuleException("CROSS_SHIP",
                        "报告船舶 " + report.shipImo() + " 与本船 " + ship.imo() + " 不符，拒绝跨船互认");
            }
            if (!report.flagCode().equals(rule.flagCode())) {
                throw new BusinessRuleException("FLAG_RULE_MISMATCH",
                        "报告船旗 " + report.flagCode() + " 与规则船旗 "
                                + rule.flagCode() + " 不符");
            }
            Instant now = executor.now();
            Instant deadline = report.completedAt()
                    .plus(Duration.ofDays(rule.maxEvidenceDays()));
            if (now.isAfter(deadline)) {
                throw new BusinessRuleException("EVIDENCE_EXPIRED",
                        "证据已超期：完成时刻 " + report.completedAt() + " + "
                                + rule.maxEvidenceDays() + " 天 = " + deadline
                                + "，当前 " + now + "（恰到截止允许）");
            }

            Set<SurveyItem> ruleItems = Set.copyOf(ruleRepository.findItems(rule.id()));
            Map<SurveyItem, ItemConclusion> conclusions = reportRepository
                    .findItems(report.id()).stream()
                    .collect(Collectors.toMap(InspectionReportItem::itemCode,
                            InspectionReportItem::conclusion));
            for (SurveyItem item : conclusions.keySet()) {
                if (!ruleItems.contains(item)) {
                    throw new BusinessRuleException("UNKNOWN_ITEM_MAPPING",
                            "报告项目 " + item + " 不在规则版本 " + rule.ruleVersion()
                                    + " 的映射范围内");
                }
            }
            List<SurveyItem> mapped = ruleItems.stream().sorted().toList();
            for (SurveyItem item : mapped) {
                ItemConclusion conclusion = conclusions.get(item);
                if (conclusion == null) {
                    throw new BusinessRuleException("EVIDENCE_ITEM_MISSING",
                            "证据缺项：报告缺少规则映射项目 " + item + " 的结论");
                }
                if (conclusion != ItemConclusion.PASS) {
                    throw new BusinessRuleException("ITEM_NOT_QUALIFIED",
                            "项目 " + item + " 结论为 " + conclusion
                                    + "，有重大缺陷或不合格的报告不得当合格项目");
                }
            }

            List<BindingResponse> bindings = new ArrayList<>();
            for (SurveyItem item : mapped) {
                RecognitionBinding binding;
                try {
                    binding = bindingRepository.insert(
                            request.requestId(), survey.id(), report.id(),
                            report.reportNo(), report.revision(),
                            rule.id(), rule.ruleVersion(), item, fp, now);
                } catch (RecognitionBindingRepository.ActiveBindingConflict e) {
                    throw new ConflictException("BINDING_EXISTS",
                            "检验 " + survey.id() + " 的项目 " + item
                                    + " 已存在有效互认绑定，每个项目只一个有效绑定");
                }
                bindings.add(BindingResponse.from(binding));
            }
            return new ApplyRecognitionResponse(survey.id(), report.reportNo(),
                    report.revision(), rule.ruleVersion(), bindings);
        });
    }

    /** 互认绑定查询（按检验）。 */
    public List<BindingResponse> bindingsOfSurvey(long surveyId) {
        surveyRepository.findById(surveyId)
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + surveyId));
        return bindingRepository.findBySurvey(surveyId).stream()
                .map(BindingResponse::from).toList();
    }
}
