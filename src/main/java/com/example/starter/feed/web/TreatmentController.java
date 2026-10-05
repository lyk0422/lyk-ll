package com.example.starter.feed.web;

import com.example.starter.feed.dto.TreatmentReportRequest;
import com.example.starter.feed.dto.TreatmentReportResponse;
import com.example.starter.feed.service.IdempotencyService;
import com.example.starter.feed.service.TreatmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治疗性用药直报接口：养殖端按批次上报治疗用药，触发休药期重算与冲突预约撤销。
 */
@RestController
@RequestMapping("/api")
public class TreatmentController {

    private final TreatmentService treatmentService;

    public TreatmentController(TreatmentService treatmentService) {
        this.treatmentService = treatmentService;
    }

    /** 治疗用药直报（requestId 幂等）；目录外药品、单人直报 422，同键异参 409。 */
    @PostMapping("/treatment-reports")
    public ResponseEntity<TreatmentReportResponse> report(@Valid @RequestBody TreatmentReportRequest request) {
        IdempotencyService.Result<TreatmentReportResponse> result = treatmentService.reportTreatment(request);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }
}
