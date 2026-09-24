package com.example.starter.blood.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.blood.service.BloodUnitService;
import com.example.starter.blood.service.TestResultAck;

/**
 * 成分血单位接口：入库、检测结果回传、状态查询。
 */
@RestController
@RequestMapping("/api/units")
public class BloodUnitController {

    private final BloodUnitService unitService;

    public BloodUnitController(BloodUnitService unitService) {
        this.unitService = unitService;
    }

    /**
     * 成分血入库，初始为待检；重复条码返回 409。
     *
     * @param request 入库请求
     * @return 入库后的单位
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UnitResponse checkIn(@Valid @RequestBody CheckInRequest request) {
        return UnitResponse.from(
                unitService.checkIn(request.barcode(), request.componentType(), request.expireDate()));
    }

    /**
     * 检测结果回传（初检/复检可乱序）；终态后回传仅记录迟到事件。
     *
     * @param barcode 条码
     * @param request 回传请求
     * @return 回传应答
     */
    @PostMapping("/{barcode}/test-results")
    public TestResultAck applyTestResult(@PathVariable String barcode,
                                         @Valid @RequestBody TestResultRequest request) {
        return unitService.applyTestResult(barcode, request.testType(), request.result());
    }

    /**
     * 查询单位当前状态。
     *
     * @param barcode 条码
     * @return 单位视图
     */
    @GetMapping("/{barcode}")
    public UnitResponse getUnit(@PathVariable String barcode) {
        return UnitResponse.from(unitService.getUnit(barcode));
    }
}
