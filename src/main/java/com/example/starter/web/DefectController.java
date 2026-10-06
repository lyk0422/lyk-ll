package com.example.starter.web;

import com.example.starter.service.DefectService;
import com.example.starter.service.QueryService;
import com.example.starter.web.dto.ApiDtos.DefectDetailResponse;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缺陷登记与查询入口。
 */
@RestController
@RequestMapping("/api/defects")
public class DefectController {

    private final DefectService defectService;
    private final QueryService queryService;

    public DefectController(DefectService defectService, QueryService queryService) {
        this.defectService = defectService;
        this.queryService = queryService;
    }

    @PostMapping
    public DefectResponse register(@Valid @RequestBody RegisterDefectRequest req) {
        return defectService.registerDefect(req);
    }

    @GetMapping("/{defectId}")
    public DefectDetailResponse detail(@PathVariable long defectId) {
        return queryService.defectDetail(defectId);
    }
}
