package com.example.starter.ventilation.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ventilation.dto.ActivateRequest;
import com.example.starter.ventilation.dto.ArchiveRequest;
import com.example.starter.ventilation.dto.EventView;
import com.example.starter.ventilation.dto.FeedbackRequest;
import com.example.starter.ventilation.dto.FeedbackResult;
import com.example.starter.ventilation.dto.ModeView;
import com.example.starter.ventilation.dto.ResetRequest;
import com.example.starter.ventilation.service.VentilationService;

import jakarta.validation.Valid;

/**
 * 隧道火灾通风联动 API。
 */
@RestController
@RequestMapping("/api/ventilation")
public class VentilationController {

    private final VentilationService service;

    public VentilationController(VentilationService service) {
        this.service = service;
    }

    /** 激活火灾模式，按预案生成“先阀后机”设备指令序列。 */
    @PostMapping("/modes/activate")
    public ModeView activate(@Valid @RequestBody ActivateRequest request) {
        return service.activate(request);
    }

    /** 火警解除，逆序复位（先停风机、到位后关风阀）。 */
    @PostMapping("/modes/reset")
    public ModeView reset(@Valid @RequestBody ResetRequest request) {
        return service.reset(request);
    }

    /** 归档火灾模式（终态，重复归档幂等成功）。 */
    @PostMapping("/modes/archive")
    public ModeView archive(@Valid @RequestBody ArchiveRequest request) {
        return service.archive(request);
    }

    /** 设备到位反馈。 */
    @PostMapping("/feedback")
    public FeedbackResult feedback(@Valid @RequestBody FeedbackRequest request) {
        return service.feedback(request);
    }

    /** 查询模式全量视图。 */
    @GetMapping("/modes/{requestId}")
    public ModeView getMode(@PathVariable String requestId) {
        return service.getMode(requestId);
    }

    /** 按序查询事件；不带 requestId 时返回全量事件流。 */
    @GetMapping("/events")
    public List<EventView> events(@RequestParam(name = "requestId", required = false) String requestId) {
        return service.listEvents(requestId);
    }

    /**
     * 手动触发一次到位超时扫描（测试/运维用）：恰满 60 秒未到位的首发指令重发，
     * 重发后再满 60 秒转故障并升级告警。返回本轮处理的指令编码。
     */
    @PostMapping("/sweep-timeouts")
    public List<String> sweepTimeouts() {
        return service.sweepTimeouts();
    }
}
