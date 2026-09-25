package com.example.starter.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.dto.ActionResult;
import com.example.starter.api.dto.CardView;
import com.example.starter.api.dto.CoverageRequest;
import com.example.starter.api.dto.DispatchRequest;
import com.example.starter.api.dto.InspectionRequest;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ToolRegisterRequest;
import com.example.starter.api.dto.TorqueRequest;
import com.example.starter.api.dto.TraceView;
import com.example.starter.api.dto.WitnessRequest;
import com.example.starter.service.OperationService;

/**
 * 航空装配力矩紧固与见证点控制服务 HTTP 入口。
 */
@RestController
@RequestMapping("/api")
public class OperationController {

    private final OperationService operationService;

    public OperationController(OperationService operationService) {
        this.operationService = operationService;
    }

    /** 登记力矩工具及日校验日期（合成数据）。 */
    @PostMapping("/tools")
    @ResponseStatus(HttpStatus.OK)
    public ActionResult registerTool(@Valid @RequestBody ToolRegisterRequest request) {
        return operationService.registerTool(request);
    }

    /** 派工。 */
    @PostMapping("/cards/dispatch")
    public ResponseEntity<Object> dispatch(@Valid @RequestBody DispatchRequest request) {
        return Responses.from(operationService.dispatch(request));
    }

    /** 力矩紧固测量（归档后迟到测量只记迟到事件）。 */
    @PostMapping("/torque/measure")
    public ResponseEntity<Object> measure(@Valid @RequestBody TorqueRequest request) {
        return Responses.from(operationService.measure(request));
    }

    /** 自检互检。 */
    @PostMapping("/inspections")
    public ResponseEntity<Object> inspect(@Valid @RequestBody InspectionRequest request) {
        return Responses.from(operationService.inspect(request));
    }

    /** H 点见证到场/缺席。 */
    @PostMapping("/witness")
    public ResponseEntity<Object> witness(@Valid @RequestBody WitnessRequest request) {
        return Responses.from(operationService.witness(request));
    }

    /** 见证等待升级检查（按服务当前 UTC 时间判定，恰满 2 小时升级）。 */
    @PostMapping("/witness/check")
    public ResponseEntity<Object> checkEscalation(@RequestParam String cardNo) {
        return Responses.from(operationService.checkEscalation(cardNo));
    }

    /** 进入覆盖工序。 */
    @PostMapping("/coverage")
    public ResponseEntity<Object> coverage(@Valid @RequestBody CoverageRequest request) {
        return Responses.from(operationService.coverage(request));
    }

    /** 放行归档（终态，重复归档幂等成功）。 */
    @PostMapping("/release")
    public ResponseEntity<Object> release(@Valid @RequestBody ReleaseRequest request) {
        return Responses.from(operationService.release(request));
    }

    /** 查询工序卡。 */
    @GetMapping("/cards/{cardNo}")
    public CardView getCard(@PathVariable String cardNo) {
        return operationService.getCard(cardNo);
    }

    /** 复算力矩曲线、见证链与返工台账。 */
    @GetMapping("/cards/{cardNo}/trace")
    public TraceView trace(@PathVariable String cardNo) {
        return operationService.trace(cardNo);
    }
}
