package com.example.starter.ventilation.web;

import com.example.starter.ventilation.service.VentilationModeService;
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
 * 隧道火灾通风联动 API。
 */
@RestController
@RequestMapping("/api/ventilation")
public class VentilationController {

    private final VentilationModeService service;

    public VentilationController(VentilationModeService service) {
        this.service = service;
    }

    /** 火灾模式激活：同键同参幂等返回首次结果，同键异参 409。 */
    @PostMapping("/activate")
    public ResponseEntity<ModeResponse> activate(@Valid @RequestBody ActivateRequest request) {
        ModeResponse response = service.activate(request.requestId(), request.zoneCode(),
                request.smokeDirection(), request.targetCondition());
        // 分区互斥 / 共享风阀冲突等业务冲突以 200 体内容表达（首次处理有结论），
        // 同键异参等请求级冲突由全局异常处理器返回 409。
        return ResponseEntity.ok(response);
    }

    /** 火警解除复位：逆序先停风机后关风阀。 */
    @PostMapping("/reset")
    public ResponseEntity<ModeResponse> reset(@Valid @RequestBody ResetRequest request) {
        return ResponseEntity.ok(service.reset(request.requestId(), request.zoneCode()));
    }

    /** 模式归档：终态，重复归档幂等成功。 */
    @PostMapping("/archive")
    public ResponseEntity<ModeResponse> archive(@Valid @RequestBody ArchiveRequest request) {
        return ResponseEntity.ok(service.archive(request.requestId(), request.zoneCode()));
    }

    /** 设备到位反馈（模拟机电系统回调）。 */
    @PostMapping("/feedback")
    public ResponseEntity<FeedbackResponse> feedback(@Valid @RequestBody FeedbackRequest request) {
        return ResponseEntity.ok(service.feedback(request.requestId(), request.sessionId(),
                request.deviceCode(), request.result()));
    }

    /** 查询会话状态与全部指令序列。 */
    @GetMapping("/sessions/{sessionId}")
    public SessionView session(@PathVariable long sessionId) {
        return service.getSession(sessionId);
    }

    /** 手动触发一次超时巡检（测试/排障入口；生产由调度器周期执行）。 */
    @PostMapping("/admin/scan-timeouts")
    public ResponseEntity<java.util.Map<String, Object>> scanTimeouts() {
        int processed = service.scanTimeouts();
        return ResponseEntity.status(HttpStatus.OK)
                .body(java.util.Map.of("processed", processed));
    }
}
