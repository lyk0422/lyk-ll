package com.example.starter.web;

import com.example.starter.service.TailDeviceService;
import com.example.starter.web.dto.RegisterTailDeviceRequest;
import com.example.starter.web.dto.SelfTestRequest;
import com.example.starter.web.dto.TailDeviceResponse;
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
 * 列尾装置档案 API：编号建档、自检更新、装置查询。
 */
@RestController
@RequestMapping("/api/tail-devices")
public class TailDeviceController {

    private final TailDeviceService service;

    public TailDeviceController(TailDeviceService service) {
        this.service = service;
    }

    /**
     * 装置建档（初始版本为1）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TailDeviceResponse register(@Valid @RequestBody RegisterTailDeviceRequest request) {
        return service.register(request);
    }

    /**
     * 自检更新（携带期望版本，成功后版本加1）。
     */
    @PostMapping("/{deviceNo}/self-tests")
    public TailDeviceResponse updateSelfTest(@PathVariable String deviceNo,
                                             @Valid @RequestBody SelfTestRequest request) {
        return service.updateSelfTest(deviceNo, request);
    }

    /**
     * 查询装置视图（含当前绑定作业与版本）。
     */
    @GetMapping("/{deviceNo}")
    public TailDeviceResponse getDevice(@PathVariable String deviceNo) {
        return service.getDevice(deviceNo);
    }
}
