package com.example.starter.survey.service;

import com.example.starter.survey.data.FlagRecognitionRepository;
import com.example.starter.survey.data.FlagReportRepository;
import com.example.starter.survey.data.FlagRiskNoteRepository;
import com.example.starter.survey.data.FlagRuleRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.FlagRecognition;
import com.example.starter.survey.domain.FlagReport;
import com.example.starter.survey.domain.FlagReportItem;
import com.example.starter.survey.domain.FlagReportRevision;
import com.example.starter.survey.domain.FlagRule;
import com.example.starter.survey.domain.FlagRuleItem;
import com.example.starter.survey.domain.ItemConclusion;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyRecognitionRequest;
import com.example.starter.survey.web.dto.EvidenceChainResponse;
import com.example.starter.survey.web.dto.FlagRecognitionResponse;
import com.example.starter.survey.web.dto.SurveyResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 限定范围互认：把报告某项目结论绑定到当前本船未签发检验。
 * 互认只替代映射项目的完成证明，不跳过原年度窗口、重大缺陷整改或证书签发门禁。
 * 申请时行锁报告并冻结当前修订与规则版本，与报告更正互斥。
 */
@Service
public class FlagRecognitionService {

    private final FlagRecognitionRepository recognitionRepository;
    private final FlagReportRepository reportRepository;
    private final FlagRuleRepository ruleRepository;
    private final FlagRiskNoteRepository riskNoteRepository;
    private final SurveyRepository surveyRepository;
    private final ShipRepository shipRepository;
    private final IdempotentExecutor executor;

    public FlagRecognitionService(FlagRecognitionRepository recognitionRepository,
                                  FlagReportRepository reportRepository,
                                  FlagRuleRepository ruleRepository,
                                  FlagRiskNoteRepository riskNoteRepository,
                                  SurveyRepository surveyRepository,
                                  ShipRepository shipRepository,
                                  IdempotentExecutor executor) {
        this.recognitionRepository = recognitionRepository;
        this.reportRepository = reportRepository;
        this.ruleRepository = ruleRepository;
        this.riskNoteRepository = riskNoteRepository;
        this.surveyRepository = surveyRepository;
        this.shipRepository = shipRepository;
        this.executor = executor;
    }

    /**
     * 申请互认（幂等）。拒绝情形：跨船、未知映射、证据缺项、证据过期
     * （now 晚于完成时刻+规则最长有效天数；恰到截止允许）、结论非合格、
     * 检验已签发、同项目已有有效绑定。
     */
    public ApiResult<FlagRecognitionResponse> apply(ApplyRecognitionRequest request) {
        Survey survey = surveyRepository.findById(request.surveyId())
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + request.surveyId()));
        FlagReport report = reportRepository.findByReportNo(request.reportNo())
                .orElseThrow(() -> new NotFoundException("REPORT_NOT_FOUND",
                        "报告不存在: " + request.reportNo()));
        FlagRule rule = ruleRepository.findByVersion(request.ruleVersion())
                .orElseThrow(() -> new NotFoundException("RULE_NOT_FOUND",
                        "互认规则版本不存在: " + request.ruleVersion()));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportNo", request.reportNo());
        fields.put("surveyId", request.surveyId());
        fields.put("ruleVersion", request.ruleVersion());
        fields.put("itemCode", request.itemCode());
        String fp = Fingerprint.of(request.requestId(), "APPLY_FLAG_RECOGNITION", fields);

        return executor.run(request.requestId(), fp, FlagRecognitionResponse.class,
                () -> doApply(request, survey, report, rule, fp));
    }

    private FlagRecognitionResponse doApply(ApplyRecognitionRequest request, Survey survey,
                                            FlagReport report, FlagRule rule, String fp) {
        if (survey.status() == SurveyStatus.CERTIFIED) {
            throw new BusinessRuleException("SURVEY_ALREADY_CERTIFIED",
                    "检验已签发，不能申请互认: surveyId=" + survey.id());
        }
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();
        if (!ship.imo().equals(report.shipImo())) {
            throw new BusinessRuleException("CROSS_SHIP_EVIDENCE",
                    "报告船 " + report.shipImo() + " 与检验船 " + ship.imo() + " 不一致，拒绝跨船互认");
        }
        if (!rule.flagCode().equals(report.flagCode())) {
            throw new BusinessRuleException("FLAG_MISMATCH",
                    "报告船旗 " + report.flagCode() + " 与规则船旗 " + rule.flagCode() + " 不一致");
        }
        FlagRuleItem mapping = ruleRepository.findItem(rule.id(), request.itemCode())
                .orElseThrow(() -> new BusinessRuleException("UNKNOWN_MAPPING",
                        "规则版本 " + rule.version() + " 未映射项目: " + request.itemCode()));
        if (mapping.surveyType() != survey.type()) {
            throw new BusinessRuleException("UNKNOWN_MAPPING",
                    "项目 " + request.itemCode() + " 映射到 " + mapping.surveyType()
                            + "，不适用于检验类型 " + survey.type());
        }
        // 行锁报告：与并发更正互斥，冻结一致的当前修订。
        FlagReport locked = reportRepository.findByIdForUpdate(report.id()).orElseThrow();
        FlagReportRevision revision = reportRepository
                .findRevision(report.id(), locked.currentRevision()).orElseThrow();
        FlagReportItem evidence = reportRepository.findItems(revision.id()).stream()
                .filter(i -> i.itemCode().equals(request.itemCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("EVIDENCE_ITEM_MISSING",
                        "报告缺少项目 " + request.itemCode() + " 的结论，证据缺项"));
        if (evidence.conclusion() != ItemConclusion.PASS) {
            throw new BusinessRuleException("EVIDENCE_NOT_QUALIFIED",
                    "项目 " + request.itemCode() + " 结论为 " + evidence.conclusion()
                            + "，有重大缺陷或不合格的报告不得当合格项目");
        }
        Instant now = executor.now();
        Instant deadline = revision.completedAt()
                .plus(Duration.ofDays(rule.maxEvidenceAgeDays()));
        if (now.isAfter(deadline)) {
            throw new BusinessRuleException("EVIDENCE_EXPIRED",
                    "证据已过期：完成时刻 " + revision.completedAt() + " + "
                            + rule.maxEvidenceAgeDays() + " 天 = " + deadline + "，当前 " + now);
        }
        recognitionRepository.findActive(survey.id(), request.itemCode()).ifPresent(r -> {
            throw new ConflictException("RECOGNITION_EXISTS",
                    "该检验项目已有有效互认绑定: recognitionId=" + r.id());
        });
        try {
            FlagRecognition saved = recognitionRepository.insert(
                    request.requestId(), survey.id(), report.id(),
                    locked.currentRevision(), rule.version(), request.itemCode(),
                    fp, now);
            return FlagRecognitionResponse.from(saved, report.reportNo());
        } catch (FlagRecognitionRepository.ActiveRecognitionConflict e) {
            throw new ConflictException("RECOGNITION_EXISTS",
                    "并发申请：同一检验同一项目仅受理一个有效互认绑定");
        }
    }

    /** 查询检验的互认绑定列表（含已失效）。 */
    public List<FlagRecognitionResponse> listBySurvey(long surveyId) {
        surveyRepository.findById(surveyId)
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + surveyId));
        return recognitionRepository.findBySurvey(surveyId).stream()
                .map(this::view)
                .toList();
    }

    /** 证据链查询：检验 + 互认绑定（含失效历史）+ 风险记录。 */
    public EvidenceChainResponse evidenceChain(long surveyId) {
        Survey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + surveyId));
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();
        return new EvidenceChainResponse(
                SurveyResponse.from(survey, ship.imo()),
                recognitionRepository.findBySurvey(surveyId).stream().map(this::view).toList(),
                riskNoteRepository.findBySurvey(surveyId));
    }

    private FlagRecognitionResponse view(FlagRecognition recognition) {
        String reportNo = reportRepository.findById(recognition.reportId())
                .map(FlagReport::reportNo).orElse("?");
        return FlagRecognitionResponse.from(recognition, reportNo);
    }
}
