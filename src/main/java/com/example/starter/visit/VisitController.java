package com.example.starter.visit;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 访视数据接口。
 */
@RestController
@RequestMapping("/api/trials/{trialCode}/visits")
public class VisitController {

    private final VisitService service;

    public VisitController(VisitService service) {
        this.service = service;
    }

    /**
     * 录入访视数据；已揭盲受试者的访视自动标记为排除出主要分析集。
     */
    @PostMapping
    public ResponseEntity<VisitView> addVisit(@PathVariable String trialCode,
            @Valid @RequestBody VisitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addVisit(trialCode, request));
    }

    /**
     * 查询受试者访视数据及排除标记。
     */
    @GetMapping
    public List<VisitView> listVisits(@PathVariable String trialCode,
            @RequestParam String screeningNo) {
        return service.listVisits(trialCode, screeningNo);
    }
}
