package com.example.starter.greenhouse.web;

import com.example.starter.greenhouse.dto.EvaluationRequest;
import com.example.starter.greenhouse.dto.EvaluationResponse;
import com.example.starter.greenhouse.service.EvaluationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日评估接口：查询（未封存实时复算）与封存（终态幂等）。
 */
@RestController
@RequestMapping("/api/greenhouse/evaluations")
public class EvaluationController {

    private final EvaluationService service;

    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    /** 查询日评估，参数 zoneCode、day(yyyy-MM-dd, UTC)。 */
    @GetMapping
    public EvaluationResponse get(@RequestParam String zoneCode, @RequestParam String day) {
        return service.get(zoneCode, day);
    }

    /** 封存为终态；重复封存幂等返回首次结果。 */
    @PostMapping("/seal")
    public EvaluationResponse seal(@Valid @RequestBody EvaluationRequest req) {
        return service.seal(req.zoneCode(), req.day());
    }
}
