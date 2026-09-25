package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.StationService;
import com.example.starter.web.dto.ApiDtos.BatteryReadingRequest;
import com.example.starter.web.dto.ApiDtos.BatteryReadingResponse;
import com.example.starter.web.dto.ApiDtos.CreateGeneratorRequest;
import com.example.starter.web.dto.ApiDtos.CreateStationRequest;
import com.example.starter.web.dto.ApiDtos.GeneratorResponse;
import com.example.starter.web.dto.ApiDtos.StationResponse;

/**
 * 基站、油机档案与电池读数接口（合成数据入口）。
 */
@RestController
@RequestMapping("/api/v1")
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    /**
     * 创建基站。
     */
    @PostMapping("/stations")
    @ResponseStatus(HttpStatus.CREATED)
    public StationResponse createStation(@Valid @RequestBody CreateStationRequest request) {
        return stationService.createStation(request);
    }

    /**
     * 创建油机。
     */
    @PostMapping("/generators")
    @ResponseStatus(HttpStatus.CREATED)
    public GeneratorResponse createGenerator(@Valid @RequestBody CreateGeneratorRequest request) {
        return stationService.createGenerator(request);
    }

    /**
     * 上报电池容量读数。
     */
    @PostMapping("/battery-readings")
    @ResponseStatus(HttpStatus.CREATED)
    public BatteryReadingResponse createReading(@Valid @RequestBody BatteryReadingRequest request) {
        return stationService.createReading(request);
    }
}
