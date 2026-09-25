package com.example.starter.web;

import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.ArchiveRequest;
import com.example.starter.dto.ArchiveResponse;
import com.example.starter.dto.EvaluateRequest;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.dto.TemperatureReportRequest;
import com.example.starter.dto.TemperatureReportResponse;
import com.example.starter.service.BalanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 热网水力平衡调节 API。
 *
 * <ul>
 *   <li>POST /api/temperatures          温度数据上报（归档后到达记迟到事件）</li>
 *   <li>POST /api/adjustments           步进调节令（初调节/再调节统一入口）</li>
 *   <li>POST /api/evaluations           失衡判定 JUDGMENT / 效果评估 RE_EVAL</li>
 *   <li>POST /api/archives              评估归档（终态，重复归档幂等成功）</li>
 *   <li>GET  /api/stations/{code}/distribution      温度分布复算</li>
 *   <li>GET  /api/stations/{code}/versions          开度版本链复算</li>
 *   <li>GET  /api/stations/{code}/evaluations       前后评估对比复算</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class BalanceController {

    private final BalanceService balanceService;

    public BalanceController(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @PostMapping("/temperatures")
    public TemperatureReportResponse reportTemperature(@Valid @RequestBody TemperatureReportRequest request) {
        return balanceService.reportTemperature(request);
    }

    @PostMapping("/adjustments")
    public ResponseEntity<AdjustResponse> adjust(@Valid @RequestBody AdjustRequest request) {
        AdjustResponse response = balanceService.adjust(request);
        // 仅真实首次创建任务返回 201；重放虽保留首次 taskCreated=true，仍返回 200
        boolean createdNow = response.taskCreated() && !response.replay();
        return ResponseEntity.status(createdNow ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @PostMapping("/evaluations")
    public EvaluationResponse evaluate(@Valid @RequestBody EvaluateRequest request) {
        return balanceService.evaluate(request);
    }

    @PostMapping("/archives")
    public ArchiveResponse archive(@Valid @RequestBody ArchiveRequest request) {
        return balanceService.archive(request);
    }

    @GetMapping("/stations/{stationCode}/distribution")
    public List<Map<String, Object>> distribution(@PathVariable String stationCode) {
        return balanceService.temperatureDistribution(stationCode);
    }

    @GetMapping("/stations/{stationCode}/versions")
    public List<Map<String, Object>> versions(@PathVariable String stationCode) {
        return balanceService.openingVersionChain(stationCode);
    }

    @GetMapping("/stations/{stationCode}/evaluations")
    public List<Map<String, Object>> evaluations(@PathVariable String stationCode) {
        return balanceService.evaluationHistory(stationCode);
    }
}
