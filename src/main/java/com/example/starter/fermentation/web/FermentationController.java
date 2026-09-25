package com.example.starter.fermentation.web;

import com.example.starter.fermentation.dto.BatchResponse;
import com.example.starter.fermentation.dto.CoolingRequest;
import com.example.starter.fermentation.dto.CoolingResponse;
import com.example.starter.fermentation.dto.CreateBatchRequest;
import com.example.starter.fermentation.dto.DiacetylRequest;
import com.example.starter.fermentation.dto.DiacetylResponse;
import com.example.starter.fermentation.dto.JudgeRequest;
import com.example.starter.fermentation.dto.JudgeResponse;
import com.example.starter.fermentation.dto.StartMaturationRequest;
import com.example.starter.fermentation.dto.TemperatureRequest;
import com.example.starter.fermentation.dto.TemperatureResponse;
import com.example.starter.fermentation.dto.TransferRequest;
import com.example.starter.fermentation.dto.TransferResponse;
import com.example.starter.fermentation.service.FermentationService;
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
 * 发酵批次 REST 入口：满罐、温度采样、双乙酰检测、降温指令、进入后熟、成熟度判定、清酒转出。
 */
@RestController
@RequestMapping("/api/fermentation/batches")
public class FermentationController {

    private final FermentationService service;

    public FermentationController(FermentationService service) {
        this.service = service;
    }

    /** 入罐（满罐）。 */
    @PostMapping
    public ResponseEntity<BatchResponse> create(@Valid @RequestBody CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBatch(request));
    }

    /** 查询批次当前视图。 */
    @GetMapping("/{batchNo}")
    public BatchResponse get(@PathVariable String batchNo) {
        return service.getBatch(batchNo);
    }

    /** 主酵/后熟温度采样上报（requestId 幂等）。 */
    @PostMapping("/temperatures")
    public TemperatureResponse temperature(@Valid @RequestBody TemperatureRequest request) {
        return service.reportTemperature(request);
    }

    /** 双乙酰检测上报（requestId 幂等）。 */
    @PostMapping("/diacetyl")
    public DiacetylResponse diacetyl(@Valid @RequestBody DiacetylRequest request) {
        return service.reportDiacetyl(request);
    }

    /** 降温指令：与曲线版本切换同事务（requestId 幂等）。 */
    @PostMapping("/cooling")
    public CoolingResponse cooling(@Valid @RequestBody CoolingRequest request) {
        return service.commandCooling(request);
    }

    /** 降温完成进入后熟，后熟保温计时开始。 */
    @PostMapping("/{batchNo}/maturation")
    public BatchResponse maturation(@PathVariable String batchNo,
                                    @Valid @RequestBody StartMaturationRequest request) {
        return service.startMaturation(batchNo, request.maturingAt());
    }

    /** 成熟度综合判定（requestId 幂等）。 */
    @PostMapping("/judgments")
    public JudgeResponse judge(@Valid @RequestBody JudgeRequest request) {
        return service.judge(request);
    }

    /** 清酒转出（终态，requestId 幂等，重复转出幂等成功）。 */
    @PostMapping("/transfers")
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return service.transfer(request);
    }
}
