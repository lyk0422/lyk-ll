package com.example.starter.web;

import com.example.starter.service.FaultRepairService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配网故障抢修排序与重要用户保电服务接口。
 */
@RestController
@RequestMapping("/api/repair")
public class FaultRepairController {

    private final FaultRepairService service;

    public FaultRepairController(FaultRepairService service) {
        this.service = service;
    }

    /** 故障接报（requestId 幂等，同故障点＋时段去重归并） */
    @PostMapping("/reports")
    public Map<String, Object> report(@Valid @RequestBody ReportRequest request) {
        return service.report(request);
    }

    /** 研判：命中重要用户分级并计算优先级 */
    @PostMapping("/orders/{orderNo}/assess")
    public Map<String, Object> assess(@PathVariable String orderNo, @Valid @RequestBody AssessRequest request) {
        return service.assess(orderNo, request);
    }

    /** 派单：班组冲突时低优先级挂单等待 */
    @PostMapping("/dispatches")
    public Map<String, Object> dispatch(@Valid @RequestBody DispatchRequest request) {
        return service.dispatch(request);
    }

    /** 抢修开始 */
    @PostMapping("/orders/{orderNo}/repair-start")
    public Map<String, Object> repairStart(@PathVariable String orderNo,
                                           @Valid @RequestBody RepairStartRequest request) {
        return service.repairStart(orderNo, request);
    }

    /** 复电确认：调度/现场双确认，齐备时与负荷恢复记录同事务提交 */
    @PostMapping("/orders/{orderNo}/restore-confirm")
    public Map<String, Object> restoreConfirm(@PathVariable String orderNo,
                                              @Valid @RequestBody RestoreConfirmRequest request) {
        return service.restoreConfirm(orderNo, request);
    }

    /** 工单合并：原单引用新单并保留轨迹 */
    @PostMapping("/merges")
    public Map<String, Object> merge(@Valid @RequestBody MergeRequest request) {
        return service.merge(request);
    }

    /** 日结封存（终态，重复封存幂等成功） */
    @PostMapping("/orders/{orderNo}/seal")
    public Map<String, Object> seal(@PathVariable String orderNo,
                                    @Valid @RequestBody OrderActionRequest request) {
        return service.seal(orderNo, request);
    }

    /** 封存后迟到回报：只记迟到事件 */
    @PostMapping("/orders/{orderNo}/late-report")
    public Map<String, Object> lateReport(@PathVariable String orderNo,
                                          @Valid @RequestBody LateReportRequest request) {
        return service.lateReport(orderNo, request);
    }

    /** 派单队列复算（按优先级、影响户数、接报时间排序） */
    @GetMapping("/queue")
    public List<Map<String, Object>> queue() {
        return service.queue();
    }

    /** 工单详情（含状态、优先级、双确认链、合并引用） */
    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> detail(@PathVariable String orderNo) {
        return service.detail(orderNo);
    }

    /** 工单轨迹 */
    @GetMapping("/orders/{orderNo}/events")
    public List<Map<String, Object>> events(@PathVariable String orderNo) {
        return service.events(orderNo);
    }
}
