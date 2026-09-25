package com.example.starter.gas.web;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.dto.ConcentrationReplayResponse;
import com.example.starter.gas.web.dto.DetectionRequest;
import com.example.starter.gas.web.dto.DetectionResponse;
import com.example.starter.gas.web.dto.LinkageRequest;
import com.example.starter.gas.web.dto.LinkageResponse;
import com.example.starter.gas.web.dto.PressureChainReplayResponse;
import com.example.starter.gas.web.dto.SettlementRequest;
import com.example.starter.gas.web.dto.SettlementResponse;
import com.example.starter.gas.web.dto.StationResponse;
import com.example.starter.gas.web.dto.ValveCommandRequest;
import com.example.starter.gas.web.dto.ValveCommandResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 门站加臭与压力联动 HTTP 入口。
 */
@RestController
@RequestMapping("/api/gas/stations")
public class GasStationController {

    private final GasStationService service;

    public GasStationController(GasStationService service) {
        this.service = service;
    }

    /** 运行监测：浓度+流量检测、比例加注、连续越限告警锁阀。 */
    @PostMapping("/{stationCode}/detections")
    public DetectionResponse detect(@PathVariable String stationCode,
                                    @Valid @RequestBody DetectionRequest request) {
        ensureStationMatches(stationCode, request.stationCode());
        return service.detect(request);
    }

    /** 压力调节联动：分级调压与加注调整同一事务下发。 */
    @PostMapping("/{stationCode}/linkages")
    public LinkageResponse regulate(@PathVariable String stationCode,
                                    @Valid @RequestBody LinkageRequest request) {
        ensureStationMatches(stationCode, request.stationCode());
        return service.regulate(request);
    }

    /** 锁阀/复开双人确认。 */
    @PostMapping("/{stationCode}/valve-commands")
    public ValveCommandResponse valveCommand(@PathVariable String stationCode,
                                             @Valid @RequestBody ValveCommandRequest request) {
        ensureStationMatches(stationCode, request.stationCode());
        return service.valveCommand(request);
    }

    /** 日结：消耗与累计流量勾稽，封存终态。 */
    @PostMapping("/{stationCode}/settlements")
    public SettlementResponse settle(@PathVariable String stationCode,
                                     @Valid @RequestBody SettlementRequest request) {
        ensureStationMatches(stationCode, request.stationCode());
        return service.settle(request);
    }

    /** 门站实时状态查询。 */
    @GetMapping("/{stationCode}")
    public ResponseEntity<StationResponse> getStation(@PathVariable String stationCode) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getStation(stationCode));
    }

    /** 浓度曲线复算：重放某日合格判定、连续越限计数与锁阀点。 */
    @GetMapping("/{stationCode}/replay/concentration")
    public ConcentrationReplayResponse replayConcentration(
            @PathVariable String stationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate) {
        return service.replayConcentration(stationCode, settleDate);
    }

    /** 压力链复算：重放某日恰等限值与调压动作链。 */
    @GetMapping("/{stationCode}/replay/pressure")
    public PressureChainReplayResponse replayPressure(
            @PathVariable String stationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDate) {
        return service.replayPressure(stationCode, settleDate);
    }

    /** 路径门站编码与请求体不一致属于调用方错误（400），不进入业务处理。 */
    private void ensureStationMatches(String pathStationCode, String bodyStationCode) {
        if (bodyStationCode != null && !bodyStationCode.equals(pathStationCode)) {
            throw new BadRequestException(
                    "路径门站编码 " + pathStationCode + " 与请求体 " + bodyStationCode + " 不一致");
        }
    }
}
