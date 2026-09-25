package com.example.starter.survey.service;

import com.example.starter.survey.data.CertificateRepository;
import com.example.starter.survey.data.ExtensionRepository;
import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.domain.Certificate;
import com.example.starter.survey.domain.ExtensionRequest;
import com.example.starter.survey.domain.ExtensionStatus;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyType;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.support.SurveyWindowCalculator;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplyExtensionRequest;
import com.example.starter.survey.web.dto.ApproveExtensionRequest;
import com.example.starter.survey.web.dto.ExtensionResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 年度检验展期：
 * <ul>
 *   <li>仅年度检验可展期，中间/特别检验禁止展期；</li>
 *   <li>每个检验至多展期一次，1..3 个月（恰满 3 个月合法，月度加法）；</li>
 *   <li>批准时原有效证书有效期与检验窗口末日在同一事务内更新，
 *       不会出现“已批准但证书未更新”。</li>
 * </ul>
 */
@Service
public class ExtensionService {

    private final ExtensionRepository extensionRepository;
    private final SurveyRepository surveyRepository;
    private final ShipRepository shipRepository;
    private final CertificateRepository certificateRepository;
    private final IdempotentExecutor executor;

    public ExtensionService(ExtensionRepository extensionRepository,
                            SurveyRepository surveyRepository,
                            ShipRepository shipRepository,
                            CertificateRepository certificateRepository,
                            IdempotentExecutor executor) {
        this.extensionRepository = extensionRepository;
        this.surveyRepository = surveyRepository;
        this.shipRepository = shipRepository;
        this.certificateRepository = certificateRepository;
        this.executor = executor;
    }

    /** 申请展期（PENDING，待批准）。 */
    public ApiResult<ExtensionResponse> apply(ApplyExtensionRequest request) {
        Survey survey = surveyRepository.findById(request.surveyId())
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + request.surveyId()));
        int months = request.months();
        String fp = Fingerprint.of(request.requestId(), "APPLY_EXTENSION",
                Map.of("surveyId", request.surveyId(), "months", months));

        return executor.run(request.requestId(), fp, ExtensionResponse.class, () -> {
            if (survey.type() != SurveyType.ANNUAL) {
                throw new BusinessRuleException("EXTENSION_NOT_ALLOWED",
                        "中间检验与特别检验不得展期: type=" + survey.type());
            }
            if (months < 1 || months > SurveyWindowCalculator.MAX_EXTENSION_MONTHS) {
                throw new BusinessRuleException("EXTENSION_MONTHS_INVALID",
                        "展期月数须在 1..3 之间: " + months);
            }
            extensionRepository.findBySurvey(survey.id()).ifPresent(e -> {
                throw new ConflictException("EXTENSION_EXISTS",
                        "该年度检验已申请过展期（仅可展期一次）: extensionId=" + e.id());
            });
            Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();
            Certificate certificate = certificateRepository.findValid(ship.id(), SurveyType.ANNUAL)
                    .orElseThrow(() -> new BusinessRuleException("NO_VALID_CERTIFICATE",
                            "当前无有效年度检验证书，无法展期；请先发证"));
            try {
                ExtensionRequest created = extensionRepository.insert(
                        request.requestId(), survey.id(), certificate.id(), months,
                        certificate.validUntil(), fp, executor.now());
                return ExtensionResponse.from(created);
            } catch (DuplicateKeyException e) {
                throw new ConflictException("EXTENSION_EXISTS",
                        "该年度检验已申请过展期（仅可展期一次）");
            }
        });
    }

    /**
     * 批准展期：原证书 valid_until 延后 months 个月（恰满 3 个月合法）、
     * 检验窗口末日同步延后；展期状态置 APPROVED——全部在同一事务内完成。
     */
    public ApiResult<ExtensionResponse> approve(ApproveExtensionRequest request) {
        ExtensionRequest extension = extensionRepository.findById(request.extensionId())
                .orElseThrow(() -> new NotFoundException("EXTENSION_NOT_FOUND",
                        "展期申请不存在: extensionId=" + request.extensionId()));
        String fp = Fingerprint.of(request.requestId(), "APPROVE_EXTENSION",
                Map.of("extensionId", request.extensionId()));

        return executor.run(request.requestId(), fp, ExtensionResponse.class, () -> {
            if (extension.status() != ExtensionStatus.PENDING) {
                // 重复批准为幂等成功，返回当前状态。
                return ExtensionResponse.from(extension);
            }
            Instant now = executor.now();
            Certificate certificate = certificateRepository.findById(
                            extension.certificateId() == null ? -1L : extension.certificateId())
                    .filter(c -> c.status() == com.example.starter.survey.domain.CertificateStatus.VALID)
                    .orElseThrow(() -> new BusinessRuleException("CERTIFICATE_NOT_VALID",
                            "原证书已非有效状态，不得批准展期"));
            // 月度加法：恰满 3 个月合法。
            LocalDate newValidUntil = SurveyWindowCalculator.extend(
                    extension.oldValidUntil(), extension.months());
            LocalDate newWindowEnd = SurveyWindowCalculator.extend(
                    surveyWindowEnd(extension.surveyId()), extension.months());

            // 以下三句必须在当前事务内全部成功：证书、检验窗口、展期状态原子更新。
            certificateRepository.updateValidUntil(certificate.id(), newValidUntil);
            surveyRepository.updateExtensionUntil(extension.surveyId(), newWindowEnd);
            extensionRepository.markApproved(extension.id(), certificate.id(), newValidUntil, now);

            ExtensionRequest approved = extensionRepository.findById(extension.id()).orElseThrow();
            return ExtensionResponse.from(approved);
        });
    }

    private LocalDate surveyWindowEnd(long surveyId) {
        return surveyRepository.findById(surveyId).orElseThrow().windowEnd();
    }
}
