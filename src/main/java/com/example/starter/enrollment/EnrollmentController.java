package com.example.starter.enrollment;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入组与盲态查询接口。
 */
@RestController
@RequestMapping("/api/trials/{trialCode}/enrollments")
public class EnrollmentController {

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    /**
     * 随机入组。新入组返回 201；同键同参幂等重放返回 200 且 replayed=true。
     */
    @PostMapping
    public ResponseEntity<EnrollmentResponse> enroll(@PathVariable String trialCode,
            @Valid @RequestBody EnrollmentRequest request) {
        EnrollmentResponse response = service.enroll(trialCode, request);
        HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 盲态查询：返回随机号与盲态组别编码；已揭盲受试者附带实际组别。
     */
    @GetMapping("/{screeningNo}")
    public EnrollmentView blindedView(@PathVariable String trialCode, @PathVariable String screeningNo) {
        return service.findBlindedView(trialCode, screeningNo);
    }
}
