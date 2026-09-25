package com.example.starter.api;

import com.example.starter.api.dto.EvaluationDtos;
import com.example.starter.service.EvaluationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日评估封存与查询。
 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public EvaluationDtos.EvaluationResponse seal(@Valid @RequestBody EvaluationDtos.EvaluationRequest request) {
        return evaluationService.seal(request);
    }

    @GetMapping("/{zoneCode}")
    public EvaluationDtos.EvaluationResponse get(
            @PathVariable String zoneCode,
            @RequestParam java.time.LocalDate date) {
        return evaluationService.get(zoneCode, date);
    }
}
