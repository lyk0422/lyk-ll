package com.example.starter.web;

import com.example.starter.service.EvaluationService;
import com.example.starter.web.dto.ApiDtos.CompleteEvaluationRequest;
import com.example.starter.web.dto.ApiDtos.EvaluationResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 根因评估入口。
 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/{evaluationId}/complete")
    public EvaluationResponse complete(@PathVariable long evaluationId,
                                       @Valid @RequestBody CompleteEvaluationRequest req) {
        return evaluationService.complete(evaluationId, req);
    }
}
