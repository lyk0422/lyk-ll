package com.example.starter.trial.api;

import com.example.starter.trial.api.dto.AuditEventResponse;
import com.example.starter.trial.api.dto.BlindingResponse;
import com.example.starter.trial.api.dto.EnrollRequest;
import com.example.starter.trial.api.dto.EnrollResponse;
import com.example.starter.trial.api.dto.UnblindRequest;
import com.example.starter.trial.api.dto.UnblindResponse;
import com.example.starter.trial.api.dto.VisitRequest;
import com.example.starter.trial.api.dto.VisitResponse;
import com.example.starter.trial.domain.AuditService;
import com.example.starter.trial.domain.BlindingQueryService;
import com.example.starter.trial.domain.EnrollmentService;
import com.example.starter.trial.domain.UnblindingService;
import com.example.starter.trial.domain.VisitService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 临床试验随机入组与应急揭盲 REST 入口。全部路径以受试者筛选号定位受试者。
 */
@RestController
@RequestMapping("/api/trial/subjects")
public class TrialController {

    private final EnrollmentService enrollmentService;
    private final BlindingQueryService blindingQueryService;
    private final UnblindingService unblindingService;
    private final VisitService visitService;
    private final AuditService auditService;

    public TrialController(EnrollmentService enrollmentService,
                           BlindingQueryService blindingQueryService,
                           UnblindingService unblindingService,
                           VisitService visitService,
                           AuditService auditService) {
        this.enrollmentService = enrollmentService;
        this.blindingQueryService = blindingQueryService;
        this.unblindingService = unblindingService;
        this.visitService = visitService;
        this.auditService = auditService;
    }

    /** 随机入组：按中心分层、区组长度 4、requestId 幂等。 */
    @PostMapping("/enrollments")
    @ResponseStatus(HttpStatus.OK)
    public EnrollResponse enroll(@Valid @RequestBody EnrollRequest request) {
        return enrollmentService.enroll(request);
    }

    /** 盲态查询：未揭盲不返回真实组别。 */
    @GetMapping("/{screeningNo}/blinding")
    public BlindingResponse getBlinding(@PathVariable String screeningNo) {
        return blindingQueryService.getBlinding(screeningNo);
    }

    /** 应急揭盲：两名不同授权人同事务先后确认。 */
    @PostMapping("/{screeningNo}/unblind")
    public UnblindResponse unblind(@PathVariable String screeningNo,
                                   @Valid @RequestBody UnblindRequest request) {
        return unblindingService.unblind(screeningNo, request);
    }

    /** 登记访视（合成数据）；揭盲后访视自动排除出主要分析集。 */
    @PostMapping("/{screeningNo}/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitResponse recordVisit(@PathVariable String screeningNo,
                                     @Valid @RequestBody VisitRequest request) {
        return visitService.recordVisit(screeningNo, request);
    }

    @GetMapping("/{screeningNo}/visits")
    public List<VisitResponse> listVisits(@PathVariable String screeningNo) {
        return visitService.listVisits(screeningNo);
    }

    /** 揭盲审计事件，按发生顺序返回。 */
    @GetMapping("/{screeningNo}/unblind-audit")
    public List<AuditEventResponse> listAudit(@PathVariable String screeningNo) {
        return auditService.listAudit(screeningNo);
    }
}
