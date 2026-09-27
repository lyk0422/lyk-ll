package com.example.starter.survey.service;

import com.example.starter.survey.data.CertificateRepository;
import com.example.starter.survey.data.DefectRepository;
import com.example.starter.survey.data.InspectionReportRepository;
import com.example.starter.survey.data.RecognitionBindingRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyItemCompletionRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.Certificate;
import com.example.starter.survey.domain.CertificateStatus;
import com.example.starter.survey.domain.Defect;
import com.example.starter.survey.domain.DefectSeverity;
import com.example.starter.survey.domain.DefectStatus;
import com.example.starter.survey.domain.InspectionReport;
import com.example.starter.survey.domain.RecognitionBinding;
import com.example.starter.survey.domain.BindingStatus;
import com.example.starter.survey.domain.ReportStatus;
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
import com.example.starter.survey.web.dto.CertificateResponse;
import com.example.starter.survey.web.dto.CertifyRequest;
import com.example.starter.survey.web.dto.CertifyResponse;
import com.example.starter.survey.web.dto.SurveyResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 发证/换发：签发为终态。
 * “挂起插入新证 → 旧证置 SUPERSEDED → 新证置 VALID → 检验置 CERTIFIED”全部在
 * {@link IdempotentExecutor} 的同一事务内完成；
 * uq_certificate_valid 唯一索引 + FOR UPDATE 行锁保证并发下也不会出现两张有效证书。
 */
@Service
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final SurveyRepository surveyRepository;
    private final ShipRepository shipRepository;
    private final DefectRepository defectRepository;
    private final RecognitionBindingRepository bindingRepository;
    private final InspectionReportRepository reportRepository;
    private final SurveyItemCompletionRepository completionRepository;
    private final IdempotentExecutor executor;

    public CertificateService(CertificateRepository certificateRepository,
                              SurveyRepository surveyRepository,
                              ShipRepository shipRepository,
                              DefectRepository defectRepository,
                              RecognitionBindingRepository bindingRepository,
                              InspectionReportRepository reportRepository,
                              SurveyItemCompletionRepository completionRepository,
                              IdempotentExecutor executor) {
        this.certificateRepository = certificateRepository;
        this.surveyRepository = surveyRepository;
        this.shipRepository = shipRepository;
        this.defectRepository = defectRepository;
        this.bindingRepository = bindingRepository;
        this.reportRepository = reportRepository;
        this.completionRepository = completionRepository;
        this.executor = executor;
    }

    /** 签发/换发证书（幂等；签发终态重复签发返回幂等成功，不产生新版本）。 */
    public ApiResult<CertifyResponse> certify(CertifyRequest request) {
        Survey survey = surveyRepository.findById(request.surveyId())
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + request.surveyId()));
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();
        String fp = Fingerprint.of(request.requestId(), "CERTIFY",
                Map.of("surveyId", request.surveyId()));

        return executor.run(request.requestId(), fp, CertifyResponse.class,
                () -> doCertify(survey, ship));
    }

    private CertifyResponse doCertify(Survey survey, Ship ship) {
        Instant now = executor.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        // 签发终态：重复签发直接返回该检验产生的证书（可能后续已被更新版本替代），不产生新版本。
        if (survey.status() == SurveyStatus.CERTIFIED) {
            Certificate produced = certificateRepository
                    .findVersions(ship.id(), survey.type()).stream()
                    .filter(c -> survey.id().equals(c.surveyId()))
                    .findFirst()
                    .orElseThrow(() -> new ConflictException("CERTIFICATE_MISSING",
                            "终态检验缺少证书记录"));
            return new CertifyResponse(SurveyResponse.from(survey, ship.imo()),
                    CertificateResponse.from(produced, ship.imo()), null);
        }
        if (survey.status() != SurveyStatus.BOARDED) {
            throw new ConflictException("SURVEY_NOT_BOARDED",
                    "检验尚未完成登轮，不能发证: surveyId=" + survey.id());
        }

        List<Defect> defects = defectRepository.findBySurvey(survey.id());
        defects.stream()
                .filter(d -> d.severity() == DefectSeverity.MAJOR
                        && d.status() != DefectStatus.RECTIFIED)
                .findFirst()
                .ifPresent(d -> {
                    throw new BusinessRuleException("MAJOR_DEFECT_OPEN",
                            "重大缺陷未整改，不得发证: defectId=" + d.id());
                });
        defects.stream()
                .filter(d -> d.status() == DefectStatus.REJECTED_LATE)
                .findFirst()
                .ifPresent(d -> {
                    throw new BusinessRuleException("DEFECT_OVERDUE",
                            "存在逾期未合格复查的缺陷，不得发证: defectId=" + d.id());
                });

        // 互认门禁：冻结一致修订与规则版本，不得以旧互认可用标志先发证。
        enforceRecognitionGates(survey);

        // 行锁旧有效证书，随后“挂起插入新证 -> 旧证失效 -> 新证生效”同事务完成。
        Certificate old = certificateRepository.findValidForUpdate(ship.id(), survey.type())
                .orElse(null);
        // 拿到行锁后重读检验状态：并发重复发证时，若对方已在锁等待期间完成发证，
        // 按终态幂等返回已产生的证书，不再产生新版本。
        Survey latest = surveyRepository.findById(survey.id()).orElseThrow();
        if (latest.status() == SurveyStatus.CERTIFIED) {
            Certificate produced = certificateRepository
                    .findVersions(ship.id(), survey.type()).stream()
                    .filter(c -> survey.id().equals(c.surveyId()))
                    .findFirst()
                    .orElseThrow(() -> new ConflictException("CERTIFICATE_MISSING",
                            "终态检验缺少证书记录"));
            return new CertifyResponse(SurveyResponse.from(latest, ship.imo()),
                    CertificateResponse.from(produced, ship.imo()), null);
        }
        int version = certificateRepository.nextVersion(ship.id(), survey.type());

        // 新证有效期照常到下一周期窗口末日；展期只衔接旧证到期与本次发证之间的间隙，
        // 不叠加到新证（旧证展期后的 valid_until 作为历史版本保留）。
        LocalDate validUntil = baseValidUntil(survey);

        Certificate created = certificateRepository.insert(
                ship.id(), survey.type(), version,
                CertificateStatus.VALID,
                today, validUntil, survey.id(), false, now);
        try {
            if (old != null) {
                certificateRepository.markSuperseded(old.id(), created.id());
            }
            certificateRepository.markValid(created.id());
            surveyRepository.markCertified(survey.id(), now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发发证：唯一索引保证只可能有一张有效证书，竞争方落败。
            throw new ConflictException("CERTIFY_RACE_LOST",
                    "并发发证冲突：该船该检验类型已由另一请求发证");
        }

        Survey certified = surveyRepository.findById(survey.id()).orElseThrow();
        Certificate fresh = certificateRepository.findById(created.id()).orElseThrow();
        return new CertifyResponse(SurveyResponse.from(certified, ship.imo()),
                CertificateResponse.from(fresh, ship.imo()),
                old == null ? null : old.id());
    }

    /**
     * 新证基线有效期至（UTC，含当日）：年度/中间为次年锚点 +3 个月；
     * 特别为满 5 年锚点 +3 个月。已批准展期时由调用方叠加展期月数。
     */
    private LocalDate baseValidUntil(Survey survey) {
        return switch (survey.type()) {
            case ANNUAL, INTERMEDIATE ->
                    survey.cycleAnchor().plusYears(1).plusMonths(3);
            case SPECIAL ->
                    survey.cycleAnchor().plusYears(5).plusMonths(3);
        };
    }

    /**
     * 互认与项目完成证明门禁（发证前，与缺陷检查并列）：
     * <ul>
     *   <li>按报告主键升序行锁报告当前修订（与报告更正同一锁顺序），
     *       锁后重读绑定，验证有效绑定冻结的修订仍与当前有效修订一致；</li>
     *   <li>失效绑定所涉项目若无登轮完成证明或新的有效互认覆盖，
     *       不得凭旧互认可用标志发证；</li>
     *   <li>检验类型的全部必需项目须由登轮完成证明或有效互认绑定覆盖；
     *       互认只替代映射项目的完成证明，不替代本门禁本身。</li>
     * </ul>
     */
    private void enforceRecognitionGates(Survey survey) {
        List<RecognitionBinding> bindings = bindingRepository.findBySurvey(survey.id());
        if (!bindings.isEmpty()) {
            // 先锁报告行（与更正相同的锁获取顺序），再在锁保护下重读绑定状态。
            bindings.stream().map(RecognitionBinding::reportId).distinct().sorted()
                    .forEach(reportRepository::findByIdForUpdate);
            bindings = bindingRepository.findBySurvey(survey.id());
        }
        java.util.Set<SurveyItem> recognized = new java.util.HashSet<>();
        java.util.Set<SurveyItem> invalidatedItems = new java.util.HashSet<>();
        for (RecognitionBinding binding : bindings) {
            if (binding.status() == BindingStatus.INVALIDATED) {
                invalidatedItems.add(binding.itemCode());
                continue;
            }
            InspectionReport report = reportRepository
                    .findByIdForUpdate(binding.reportId()).orElseThrow();
            if (report.status() != ReportStatus.ACTIVE
                    || report.revision() != binding.reportRevision()) {
                throw new BusinessRuleException("RECOGNITION_STALE",
                        "互认绑定冻结的修订 " + binding.reportRevision()
                                + " 与报告当前有效修订不一致，不得发证: bindingId=" + binding.id());
            }
            recognized.add(binding.itemCode());
        }
        java.util.Set<SurveyItem> covered = new java.util.HashSet<>(
                completionRepository.findBySurvey(survey.id()));
        covered.addAll(recognized);
        // 失效互认所涉项目没有任何有效覆盖：不得凭旧互认可用标志发证。
        List<SurveyItem> staleItems = invalidatedItems.stream()
                .filter(item -> !covered.contains(item)).sorted().toList();
        if (!staleItems.isEmpty()) {
            throw new BusinessRuleException("RECOGNITION_INVALIDATED",
                    "互认绑定已因报告更正失效，不得凭旧互认发证，"
                            + "须重新申请互认或补足登轮完成证明，项目: " + staleItems);
        }
        java.util.Set<SurveyItem> required = SurveyItem.requiredFor(survey.type());
        List<SurveyItem> missing = required.stream()
                .filter(item -> !covered.contains(item)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("ITEM_COMPLETION_MISSING",
                    "必需项目缺少完成证明（登轮完成或有效互认）: " + missing);
        }
    }
}
