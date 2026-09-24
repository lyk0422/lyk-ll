package com.example.starter.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.common.ApiException;
import com.example.starter.enrollment.EnrollmentRepository;
import com.example.starter.enrollment.Trial;

/**
 * 审计事件查询接口：按发生顺序（id 升序）返回。
 */
@RestController
@RequestMapping("/api/trials/{trialCode}/audit-events")
public class AuditController {

    private final AuditRepository auditRepository;
    private final EnrollmentRepository enrollmentRepository;

    public AuditController(AuditRepository auditRepository, EnrollmentRepository enrollmentRepository) {
        this.auditRepository = auditRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    /**
     * 查询审计事件；可选按筛选号过滤。揭盲审计事件按发生顺序返回。
     */
    @GetMapping
    public List<AuditEventView> list(@PathVariable String trialCode,
            @RequestParam(required = false) String screeningNo) {
        Trial trial = enrollmentRepository.findTrialByCode(trialCode);
        if (trial == null) {
            throw ApiException.notFound("TRIAL_NOT_FOUND", "试验不存在: " + trialCode);
        }
        return auditRepository.list(trial.id(), screeningNo);
    }
}
