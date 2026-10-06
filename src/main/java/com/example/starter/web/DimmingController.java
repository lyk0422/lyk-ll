package com.example.starter.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.domain.Circuit;
import com.example.starter.dto.AccidentLockRequest;
import com.example.starter.dto.AccidentUnlockRequest;
import com.example.starter.dto.BrightnessRequest;
import com.example.starter.dto.DimRequest;
import com.example.starter.dto.DimResult;
import com.example.starter.dto.TrafficRequest;
import com.example.starter.repo.CircuitRepository;
import com.example.starter.service.DimmingService;

import jakarta.validation.Valid;

/**
 * 隧道照明调光接口。
 */
@RestController
@RequestMapping("/api/circuits")
public class DimmingController {

    private final DimmingService dimmingService;
    private final CircuitRepository circuitRepo;

    public DimmingController(DimmingService dimmingService, CircuitRepository circuitRepo) {
        this.dimmingService = dimmingService;
        this.circuitRepo = circuitRepo;
    }

    /** 查询全部回路。 */
    @GetMapping
    public List<Circuit> list() {
        return circuitRepo.findAll();
    }

    /** 查询单个回路当前状态。 */
    @GetMapping("/{id}")
    public Circuit get(@PathVariable long id) {
        return dimmingService.getCircuit(id);
    }

    /** 人工调光。 */
    @PostMapping("/{id}/dim")
    public DimResult dim(@PathVariable long id, @Valid @RequestBody DimRequest request) {
        return dimmingService.dimManually(id, request);
    }

    /** 洞外亮度检测：低于合成阈值立即升一档，恰等阈值不触发。 */
    @PostMapping("/{id}/brightness-check")
    public DimResult brightnessCheck(@PathVariable long id, @Valid @RequestBody BrightnessRequest request) {
        return dimmingService.checkBrightness(id, request);
    }

    /** 车流量合成检测数据上报（5 分钟滚动更新）：恰等为 0 时降至保底亮度。 */
    @PostMapping("/{id}/traffic-update")
    public DimResult trafficUpdate(@PathVariable long id, @Valid @RequestBody TrafficRequest request) {
        return dimmingService.trafficUpdate(id, request);
    }

    /** 事故告警：调光锁定在事故模式。 */
    @PostMapping("/{id}/accident-lock")
    public DimResult accidentLock(@PathVariable long id, @Valid @RequestBody AccidentLockRequest request) {
        return dimmingService.accidentLock(id, request);
    }

    /** 事故解除：须现场确认。 */
    @PostMapping("/{id}/accident-unlock")
    public DimResult accidentUnlock(@PathVariable long id, @Valid @RequestBody AccidentUnlockRequest request) {
        return dimmingService.accidentUnlock(id, request);
    }
}
