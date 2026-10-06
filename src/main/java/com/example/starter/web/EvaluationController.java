package com.example.starter.web;

import com.example.starter.service.EvaluationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 日评估接口：输出各池稳定时长与告警处置链，可重复复算。
 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final JsonSupport jsonSupport;

    public EvaluationController(EvaluationService evaluationService, JsonSupport jsonSupport) {
        this.evaluationService = evaluationService;
        this.jsonSupport = jsonSupport;
    }

    @GetMapping("/daily")
    public ResponseEntity<String> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(jsonSupport.toJson(evaluationService.evaluate(date)));
    }
}
