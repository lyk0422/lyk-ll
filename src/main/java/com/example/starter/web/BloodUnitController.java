package com.example.starter.web;

import com.example.starter.service.BloodUnitService;
import com.example.starter.web.dto.InboundRequest;
import com.example.starter.web.dto.ResultCallbackRequest;
import com.example.starter.web.dto.UnitResponse;
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
 * 成分血单位接口：入库、检测结果回传、状态查询。
 */
@RestController
@RequestMapping("/api/units")
public class BloodUnitController {

    private final BloodUnitService service;

    public BloodUnitController(BloodUnitService service) {
        this.service = service;
    }

    /**
     * 成分血入库，初始状态为待检。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UnitResponse inbound(@Valid @RequestBody InboundRequest request) {
        return service.inbound(request);
    }

    /**
     * 检测结果回传（初检/复检，可乱序）。
     */
    @PostMapping("/{barcode}/results")
    public UnitResponse recordResult(@PathVariable String barcode,
                                     @Valid @RequestBody ResultCallbackRequest request) {
        return service.recordResult(barcode, request);
    }

    /**
     * 查询单位当前状态。
     */
    @GetMapping("/{barcode}")
    public UnitResponse getByBarcode(@PathVariable String barcode) {
        return service.getByBarcode(barcode);
    }
}
