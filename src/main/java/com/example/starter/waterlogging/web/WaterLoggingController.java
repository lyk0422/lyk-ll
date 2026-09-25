package com.example.starter.waterlogging.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.waterlogging.api.ArchiveRequest;
import com.example.starter.waterlogging.api.ArchiveResult;
import com.example.starter.waterlogging.api.LevelReadingRequest;
import com.example.starter.waterlogging.api.LossContactCheckRequest;
import com.example.starter.waterlogging.api.LossContactResult;
import com.example.starter.waterlogging.api.PlanView;
import com.example.starter.waterlogging.api.PumpCommandRequest;
import com.example.starter.waterlogging.api.PumpCommandResult;
import com.example.starter.waterlogging.api.ReadingResult;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.service.WaterLoggingService;

import jakarta.validation.Valid;

/**
 * 城市内涝泵站联动预降服务入口：
 * 气象预警→预降决策→泵站启停→水位复测→复盘归档。
 * 所有写接口以 requestId 幂等。
 */
@RestController
@RequestMapping("/api")
public class WaterLoggingController {

    public WaterLoggingController(WaterLoggingService service) {
        this.service = service;
    }

    private final WaterLoggingService service;

    /** 气象预警启动预降（橙色及以上）；首办 201，同键同参重放 200。 */
    @PostMapping("/warnings/start")
    public ResponseEntity<IdempotentResponse<PlanView>> start(@Valid @RequestBody WarningStartRequest request) {
        IdempotentResponse<PlanView> response = service.startOnWarning(request);
        return ResponseEntity.status(response.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    /** 泵站单机启停指令。 */
    @PostMapping("/pumps/commands")
    public IdempotentResponse<PumpCommandResult> command(@Valid @RequestBody PumpCommandRequest request) {
        return service.commandPump(request);
    }

    /** 液位复测数据上报。 */
    @PostMapping("/readings")
    public IdempotentResponse<ReadingResult> reading(@Valid @RequestBody LevelReadingRequest request) {
        return service.reportReading(request);
    }

    /** 失联判定检查（恰满 30 分钟无数据派单）。 */
    @PostMapping("/loss-contact/checks")
    public IdempotentResponse<LossContactResult> lossCheck(@Valid @RequestBody LossContactCheckRequest request) {
        return service.checkLossContact(request);
    }

    /** 复盘归档（终态，重复归档幂等成功）。 */
    @PostMapping("/reviews/archive")
    public IdempotentResponse<ArchiveResult> archive(@Valid @RequestBody ArchiveRequest request) {
        return service.archive(request);
    }
}
