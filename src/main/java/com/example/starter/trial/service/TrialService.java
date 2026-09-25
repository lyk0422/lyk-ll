package com.example.starter.trial.service;

import com.example.starter.trial.api.AuditEventResponse;
import com.example.starter.trial.api.BlindedSubjectResponse;
import com.example.starter.trial.api.EnrollmentRequest;
import com.example.starter.trial.api.EnrollmentResponse;
import com.example.starter.trial.api.UnblindingConfirmRequest;
import com.example.starter.trial.api.UnblindingResponse;
import com.example.starter.trial.api.VisitRequest;
import com.example.starter.trial.api.VisitResponse;

import java.util.List;

/**
 * 临床试验随机入组与应急揭盲业务服务（骨架，实现待补充）。
 */
public interface TrialService {

    EnrollmentResponse enroll(EnrollmentRequest request);

    BlindedSubjectResponse getBlindedSubject(String trialId, String subjectScreenNo);

    UnblindingResponse confirmUnblinding(UnblindingConfirmRequest request);

    UnblindingResponse getUnblindingStatus(String trialId, String subjectScreenNo);

    List<AuditEventResponse> listAuditEvents(String trialId, String subjectScreenNo);

    VisitResponse registerVisit(String trialId, String subjectScreenNo, VisitRequest request);

    List<VisitResponse> listVisits(String trialId, String subjectScreenNo);
}
