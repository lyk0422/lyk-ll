package com.example.starter.cutover.web;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.EventView;
import com.example.starter.cutover.dto.ExpireCheckRequest;
import com.example.starter.cutover.dto.RequestView;
import com.example.starter.cutover.dto.SealRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.service.CutoverService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据中心割接窗口与回退判据 REST 接口。
 */
@RestController
@RequestMapping("/api/cutovers")
public class CutoverController {

    private final CutoverService service;

    public CutoverController(CutoverService service) {
        this.service = service;
    }

    /** 提交割接申请（requestId 幂等）；首次 201，同键同参重放 200。 */
    @PostMapping
    public ResponseEntity<ActionResult> create(@Valid @RequestBody CreateCutoverRequest req) {
        ActionResult result = service.create(req);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    /** 查询申请详情（含影响分析清单与步骤时序）。 */
    @GetMapping("/{requestId}")
    public RequestView get(@PathVariable String requestId) {
        return service.get(requestId);
    }

    /** 执行影响分析，列出设备关联的全部业务系统。 */
    @PostMapping("/{requestId}/impact-analysis")
    public ActionResult analyze(@PathVariable String requestId) {
        return service.analyze(requestId);
    }

    /** 审批。 */
    @PostMapping("/approve")
    public ActionResult approve(@Valid @RequestBody ApproveRequest req) {
        return service.approve(req);
    }

    /** 进入窗口执行（批准后、首步回报前调用）。 */
    @PostMapping("/execute")
    public ActionResult execute(@Valid @RequestBody StartExecutionRequest req) {
        return service.startExecution(req);
    }

    /** 步骤逐条回报（requestUid 幂等；失败命中回退判据）。 */
    @PostMapping("/steps/report")
    public ActionResult report(@Valid @RequestBody StepReportRequest req) {
        return service.report(req);
    }

    /** 窗口超期巡检：窗口结束仍有未完成步骤则强制回退。 */
    @PostMapping("/expire-check")
    public ActionResult expireCheck(@Valid @RequestBody ExpireCheckRequest req) {
        return service.expireCheck(req);
    }

    /** 验证封板（终态，重复封板幂等成功）。 */
    @PostMapping("/seal")
    public ActionResult seal(@Valid @RequestBody SealRequest req) {
        return service.seal(req);
    }

    /** 复算查询：事件/回退判据命中记录。 */
    @GetMapping("/{requestId}/events")
    public List<EventView> events(@PathVariable String requestId) {
        return service.events(requestId);
    }
}
