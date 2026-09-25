package com.example.starter.controller;

import java.util.List;

import com.example.starter.dto.GrainDtos.CreateFanRequest;
import com.example.starter.dto.GrainDtos.CreateSiloRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.LateEventView;
import com.example.starter.dto.GrainDtos.ReadingRequest;
import com.example.starter.dto.GrainDtos.ReadingResponse;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.dto.GrainDtos.TemperaturePoint;
import com.example.starter.service.SiloService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 粮仓与监测接口：档案创建、温湿度上报（告警判定）、温度曲线与迟到事件查询。
 */
@RestController
@RequestMapping("/api")
public class SiloController {

    private final SiloService siloService;

    public SiloController(SiloService siloService) {
        this.siloService = siloService;
    }

    /** 创建粮仓（合成数据）。 */
    @PostMapping("/silos")
    public ResponseEntity<SiloView> createSilo(@Valid @RequestBody CreateSiloRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(siloService.createSilo(request));
    }

    /** 查询粮仓。 */
    @GetMapping("/silos/{siloId}")
    public SiloView getSilo(@PathVariable long siloId) {
        return siloService.getSilo(siloId);
    }

    /** 创建风机（合成数据）。 */
    @PostMapping("/fans")
    public ResponseEntity<FanView> createFan(@Valid @RequestBody CreateFanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(siloService.createFan(request));
    }

    /**
     * 温湿度上报：任一点粮温超过安全温度即告警（恰等判正常）；
     * 归档后迟到数据只记迟到事件；requestId 幂等。
     */
    @PostMapping("/silos/{siloId}/readings")
    public ResponseEntity<ReadingResponse> ingestReading(@PathVariable long siloId,
                                                         @Valid @RequestBody ReadingRequest request) {
        ReadingResponse response = siloService.ingestReading(siloId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    /** 温度曲线（按采样时间排序，可复算）。 */
    @GetMapping("/silos/{siloId}/temperature-curve")
    public List<TemperaturePoint> temperatureCurve(@PathVariable long siloId) {
        return siloService.temperatureCurve(siloId);
    }

    /** 归档后迟到事件列表。 */
    @GetMapping("/silos/{siloId}/late-events")
    public List<LateEventView> lateEvents(@PathVariable long siloId) {
        return siloService.lateEvents(siloId);
    }
}
