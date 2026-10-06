package com.example.starter.reeval;

import com.example.starter.reeval.ReevalDtos.ReevaluateRequest;
import com.example.starter.reeval.ReevalDtos.ReevaluateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 年度风险复评接口。
 */
@RestController
@RequestMapping("/api/reevaluations")
public class ReevaluationController {

    private final ReevaluationService reevaluationService;

    public ReevaluationController(ReevaluationService reevaluationService) {
        this.reevaluationService = reevaluationService;
    }

    @PostMapping
    public ReevaluateResponse reevaluate(@Valid @RequestBody ReevaluateRequest request) {
        return reevaluationService.reevaluate(request);
    }
}
