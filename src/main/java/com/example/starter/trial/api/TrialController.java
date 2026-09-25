package com.example.starter.trial.api;

import com.example.starter.trial.service.TrialService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 随机入组、盲态查询、应急揭盲、访视登记与审计查询 HTTP 入口。
 */
@RestController
@RequestMapping("/api/trials")
public class TrialController {

    private final TrialService trialService;

    public TrialController(TrialService trialService) {
        this.trialService = trialService;
    }

    @PostMapping("/enrollments")
    public ResponseEntity<EnrollmentResponse> enroll(@Valid @RequestBody EnrollmentRequest request) {
        EnrollmentResponse response = trialService.enroll(request);
        // 首次分配 201；幂等重放返回首次结果 200。
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{trialId}/subjects/{subjectScreenNo}")
    public BlindedSubjectResponse getBlinded(@PathVariable String trialId,
                                             @PathVariable String subjectScreenNo) {
        return trialService.getBlindedSubject(trialId, subjectScreenNo);
    }

    @PostMapping("/unblindings/confirmations")
    public UnblindingResponse confirmUnblinding(@Valid @RequestBody UnblindingConfirmRequest request) {
        return trialService.confirmUnblinding(request);
    }

    @GetMapping("/{trialId}/subjects/{subjectScreenNo}/unblinding")
    public UnblindingResponse getUnblinding(@PathVariable String trialId,
                                            @PathVariable String subjectScreenNo) {
        return trialService.getUnblindingStatus(trialId, subjectScreenNo);
    }

    @GetMapping("/{trialId}/subjects/{subjectScreenNo}/audit-events")
    public List<AuditEventResponse> listAuditEvents(@PathVariable String trialId,
                                                    @PathVariable String subjectScreenNo) {
        return trialService.listAuditEvents(trialId, subjectScreenNo);
    }

    @PostMapping("/{trialId}/subjects/{subjectScreenNo}/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitResponse registerVisit(@PathVariable String trialId,
                                       @PathVariable String subjectScreenNo,
                                       @Valid @RequestBody VisitRequest request) {
        return trialService.registerVisit(trialId, subjectScreenNo, request);
    }

    @GetMapping("/{trialId}/subjects/{subjectScreenNo}/visits")
    public List<VisitResponse> listVisits(@PathVariable String trialId,
                                          @PathVariable String subjectScreenNo) {
        return trialService.listVisits(trialId, subjectScreenNo);
    }
}
