package com.example.starter.survey.service;

import com.example.starter.survey.data.CertificateRepository;
import com.example.starter.survey.data.DefectRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.Certificate;
import com.example.starter.survey.domain.Defect;
import com.example.starter.survey.domain.DefectSeverity;
import com.example.starter.survey.domain.DefectStatus;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.DefectResponse;
import com.example.starter.survey.web.dto.RectifyDefectRequest;
import com.example.starter.survey.web.dto.RectifyResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 缺陷整改材料提交与复查受理（一般缺陷 30 日闭区间，逾期证书失效）。 */
@Service
public class DefectService {

    private final DefectRepository defectRepository;
    private final SurveyRepository surveyRepository;
    private final ShipRepository shipRepository;
    private final CertificateRepository certificateRepository;
    private final IdempotentExecutor executor;

    public DefectService(DefectRepository defectRepository,
                         SurveyRepository surveyRepository,
                         ShipRepository shipRepository,
                         CertificateRepository certificateRepository,
                         IdempotentExecutor executor) {
        this.defectRepository = defectRepository;
        this.surveyRepository = surveyRepository;
        this.shipRepository = shipRepository;
        this.certificateRepository = certificateRepository;
        this.executor = executor;
    }

    /**
     * 提交整改复查（幂等）：
     * <ul>
     *   <li>一般缺陷：提交日 ≤ 发现日+30 日（恰第 30 日）受理；逾期不予受理，当前有效证书失效；</li>
     *   <li>重大缺陷：无固定期限，发证前任一日提交均受理；</li>
     *   <li>已处理缺陷换新 requestId 再提交返回 409。</li>
     * </ul>
     */
    public ApiResult<RectifyResponse> rectify(RectifyDefectRequest request) {
        Defect defect = defectRepository.findById(request.defectId())
                .orElseThrow(() -> new NotFoundException("DEFECT_NOT_FOUND",
                        "缺陷不存在: defectId=" + request.defectId()));
        Survey survey = surveyRepository.findById(defect.surveyId()).orElseThrow();
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();

        String fp = Fingerprint.of(request.requestId(), "RECTIFY_DEFECT",
                Map.of("defectId", request.defectId(),
                        "submitDate", request.submitDate().toString(),
                        "note", request.note() == null ? "" : request.note()));

        return executor.run(request.requestId(), fp, RectifyResponse.class, () -> {
            Instant now = executor.now();
            if (defect.status() != DefectStatus.OPEN) {
                throw new ConflictException("DEFECT_ALREADY_HANDLED",
                        "缺陷已处理，状态: " + defect.status());
            }
            boolean accepted;
            if (defect.severity() == DefectSeverity.MAJOR) {
                accepted = true;
            } else {
                accepted = !request.submitDate().isAfter(defect.deadline());
            }
            String note = request.note();
            Long invalidatedCertId = null;
            if (accepted) {
                defectRepository.markRectified(defect.id(), request.submitDate(), note, now);
            } else {
                String rejectNote = "逾期提交：提交日 " + request.submitDate()
                        + " 晚于复查截止日 " + defect.deadline() + "（UTC，含当日）";
                defectRepository.markRejectedLate(defect.id(), request.submitDate(),
                        note == null ? rejectNote : note + "; " + rejectNote, now);
                // 逾期证书失效：与逾期受理结果在同一事务内完成。
                invalidatedCertId = certificateRepository
                        .findValid(ship.id(), survey.type())
                        .map(Certificate::id)
                        .orElse(null);
                if (invalidatedCertId != null) {
                    certificateRepository.markExpired(invalidatedCertId);
                }
            }
            Defect updated = defectRepository.findById(defect.id()).orElseThrow();
            return new RectifyResponse(DefectResponse.from(updated), accepted, invalidatedCertId);
        });
    }

    /** 检验下的缺陷链。 */
    public List<DefectResponse> listBySurvey(long surveyId) {
        return defectRepository.findBySurvey(surveyId).stream()
                .map(DefectResponse::from).toList();
    }
}
