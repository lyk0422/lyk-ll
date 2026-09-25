package com.example.starter.rt.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.rt.domain.MetricType;
import com.example.starter.rt.dto.ConstraintItemRequest;
import com.example.starter.rt.dto.CreatePatientRequest;
import com.example.starter.rt.dto.CreatePlanRequest;
import com.example.starter.rt.dto.CreateTemplateRequest;
import com.example.starter.rt.dto.OarDoseRequest;
import com.example.starter.rt.dto.PlanVersionResponse;
import com.example.starter.rt.repository.DirectoryRepository;
import com.example.starter.rt.repository.PlanRepository;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.web.ApiException;

/**
 * 合成目录与计划设计服务：建档患者、约束模板，创建计划新版本（DESIGN）。
 */
@Service
public class PlanAdminService {

    private final DirectoryRepository directory;
    private final PlanRepository planRepository;
    private final Clock clock;

    public PlanAdminService(DirectoryRepository directory, PlanRepository planRepository, Clock clock) {
        this.directory = directory;
        this.planRepository = planRepository;
        this.clock = clock;
    }

    /** 新建合成患者；同 ID 重复创建返回 409。 */
    @Transactional
    public void createPatient(CreatePatientRequest request) {
        try {
            boolean inserted = directory.insertPatient(
                    request.patientId(), request.patientName(), LocalDateTime.now(clock));
            if (!inserted) {
                throw ApiException.conflict("PATIENT_EXISTS", "患者已存在：" + request.patientId());
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PATIENT_EXISTS", "患者已存在：" + request.patientId());
        }
    }

    /** 新建约束模板及逐项限值。 */
    @Transactional
    public void createTemplate(CreateTemplateRequest request) {
        validateTemplateItems(request.items());
        try {
            boolean inserted = directory.insertTemplate(
                    request.templateCode(), request.templateName(), LocalDateTime.now(clock));
            if (!inserted) {
                throw ApiException.conflict("TEMPLATE_EXISTS", "约束模板已存在：" + request.templateCode());
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("TEMPLATE_EXISTS", "约束模板已存在：" + request.templateCode());
        }
        for (ConstraintItemRequest item : request.items()) {
            directory.insertConstraintItem(request.templateCode(), item.organCode(), item.organName(),
                    item.metricType().name(), item.metricRef(), item.limitValue());
        }
    }

    /** 创建计划新版本（同 planCode 再次调用即换版，新版本为 DESIGN，旧版在重新批准的同一事务失效）。 */
    @Transactional
    public PlanVersionResponse createPlan(CreatePlanRequest request) {
        if (!directory.patientExists(request.patientId())) {
            throw ApiException.notFound("PATIENT_NOT_FOUND", "患者不存在：" + request.patientId());
        }
        if (!directory.templateExists(request.templateCode())) {
            throw ApiException.notFound("TEMPLATE_NOT_FOUND", "约束模板不存在：" + request.templateCode());
        }
        validateOarDoses(request.oarDoses());

        LocalDateTime now = LocalDateTime.now(clock);
        int versionNo = planRepository.nextVersionNo(request.patientId(), request.planCode());
        long versionId;
        try {
            versionId = planRepository.insertVersion(request.patientId(), request.planCode(), versionNo,
                    request.templateCode(), request.prescribedTotalDoseCgy(),
                    request.fractionDoseCgy(), request.fractionCount(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PLAN_VERSION_EXISTS",
                    "计划版本并发冲突：" + request.planCode() + "#v" + versionNo);
        }
        for (OarDoseRowDto dose : request.oarDoses().stream().map(OarDoseRowDto::from).toList()) {
            planRepository.insertOarDose(versionId, dose.organCode(), dose.metricType(),
                    dose.metricRef(), dose.metricValue());
        }
        PlanVersionRow row = planRepository.findVersionById(versionId).orElseThrow();
        return Responses.toPlanVersion(row);
    }

    private void validateTemplateItems(List<ConstraintItemRequest> items) {
        for (ConstraintItemRequest item : items) {
            if (item.metricType() == MetricType.V_DOSE_PCT && item.metricRef() == null) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "V_DOSE_PCT 约束项必须提供参照剂量 metricRef：" + item.organCode());
            }
            if (item.metricType() == MetricType.DMAX_CGY && item.metricRef() != null) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "DMAX_CGY 约束项不应提供参照剂量 metricRef：" + item.organCode());
            }
        }
    }

    private void validateOarDoses(List<OarDoseRequest> doses) {
        for (OarDoseRequest dose : doses) {
            if (dose.metricType() == MetricType.V_DOSE_PCT && dose.metricRef() == null) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "V_DOSE_PCT 申报项必须提供参照剂量 metricRef：" + dose.organCode());
            }
            if (dose.metricType() == MetricType.DMAX_CGY && dose.metricRef() != null) {
                throw new ApiException(400, "VALIDATION_FAILED",
                        "DMAX_CGY 申报项不应提供参照剂量 metricRef：" + dose.organCode());
            }
        }
    }

    /** OAR 申报内部传输对象。 */
    private record OarDoseRowDto(String organCode, String metricType, Long metricRef,
                                 java.math.BigDecimal metricValue) {
        static OarDoseRowDto from(OarDoseRequest request) {
            return new OarDoseRowDto(request.organCode(), request.metricType().name(),
                    request.metricRef(), request.metricValue());
        }
    }
}
