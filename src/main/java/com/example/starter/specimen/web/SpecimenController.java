package com.example.starter.specimen.web;

import com.example.starter.specimen.service.SpecimenService;
import com.example.starter.specimen.web.dto.AliquotRequest;
import com.example.starter.specimen.web.dto.AliquotResponse;
import com.example.starter.specimen.web.dto.IntakeRequest;
import com.example.starter.specimen.web.dto.LedgerResponse;
import com.example.starter.specimen.web.dto.SpecimenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 样本接口：入库、分装与体积账本查询。
 */
@RestController
@RequestMapping("/api/specimens")
public class SpecimenController {

    private final SpecimenService service;

    public SpecimenController(SpecimenService service) {
        this.service = service;
    }

    /**
     * 入库：登记原始样本，specimenId 唯一。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpecimenResponse intake(@Valid @RequestBody IntakeRequest request) {
        return service.intake(request);
    }

    /**
     * 分装：按 requestId 幂等，整批生成唯一分装并精确扣减剩余量。
     */
    @PostMapping("/{specimenId}/aliquots")
    public AliquotResponse aliquot(@PathVariable String specimenId,
                                   @Valid @RequestBody AliquotRequest request) {
        return service.aliquot(specimenId, request);
    }

    /**
     * 体积账本：返回剩余量与各状态分装量汇总及守恒校验结果。
     */
    @GetMapping("/{specimenId}/ledger")
    public LedgerResponse ledger(@PathVariable String specimenId) {
        return service.ledger(specimenId);
    }
}
