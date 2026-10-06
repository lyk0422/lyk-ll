package com.example.starter.web;

import com.example.starter.service.AssessmentService;
import com.example.starter.web.dto.AssessmentRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线路安全评估入口。
 */
@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {

    private final AssessmentService assessmentService;

    public AssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    /**
     * 提交线路安全评估；通过则生成 1 年有效期运行资格并投入运行。
     */
    @PostMapping
    public AssessmentRequest.Result assess(@Valid @RequestBody AssessmentRequest request) {
        return assessmentService.assess(request);
    }
}
