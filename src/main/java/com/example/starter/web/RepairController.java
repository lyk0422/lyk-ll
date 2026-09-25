package com.example.starter.web;

import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.DispatchService;
import com.example.starter.service.EscalationService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.RestoreService;
import com.example.starter.service.WorkOrderService;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.FaultReportResponse;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.MergeResponse;
import com.example.starter.web.dto.QueueEntry;
import com.example.starter.web.dto.RestoreChainView;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.RestoreConfirmResponse;
import com.example.starter.web.dto.SealResponse;
import com.example.starter.web.dto.WorkOrderView;

/**
 * 配网故障抢修 REST 入口：接报研判、派单、复电确认、工单合并、日结封存与复算查询。
 */
@RestController
@RequestMapping("/api")
public class RepairController {

    private final FaultReportService faultReports;
    private final DispatchService dispatch;
    private final RestoreService restore;
    private final WorkOrderService workOrders;
    private final EscalationService escalation;

    public RepairController(FaultReportService faultReports, DispatchService dispatch,
            RestoreService restore, WorkOrderService workOrders, EscalationService escalation) {
        this.faultReports = faultReports;
        this.dispatch = dispatch;
        this.restore = restore;
        this.workOrders = workOrders;
        this.escalation = escalation;
    }

    /** 故障接报（含自动研判建单/归并），requestId 幂等。 */
    @PostMapping("/fault-reports")
    public FaultReportResponse report(@Validated @RequestBody FaultReportRequest req) {
        return faultReports.report(req);
    }

    /** 派单，requestId 幂等；班组冲突时挂单。 */
    @PostMapping("/dispatches")
    public DispatchResponse dispatch(@Validated @RequestBody DispatchRequest req) {
        return dispatch.dispatch(req);
    }

    /** 复电确认（调度/现场双确认），requestId 幂等。 */
    @PostMapping("/restore-confirmations")
    public RestoreConfirmResponse confirm(@Validated @RequestBody RestoreConfirmRequest req) {
        return restore.confirm(req);
    }

    /** 工单合并：源单引用新单并保留轨迹。 */
    @PostMapping("/work-orders/merge")
    public MergeResponse merge(@Validated @RequestBody MergeRequest req) {
        return workOrders.merge(req);
    }

    /** 日结封存：终态，重复封存幂等成功。 */
    @PostMapping("/work-orders/{id}/seal")
    public SealResponse seal(@PathVariable long id) {
        return workOrders.seal(id);
    }

    /** 工单详情（含轨迹、发电车事件）。 */
    @GetMapping("/work-orders/{id}")
    public WorkOrderView view(@PathVariable long id) {
        return workOrders.view(id);
    }

    /** 派单队列复算：按重要等级、影响户数降序、接报先后排序。 */
    @GetMapping("/dispatch-queue")
    public List<QueueEntry> queue(@RequestParam(required = false) String feederId) {
        return dispatch.queue(feederId);
    }

    /** 复电确认链复算。 */
    @GetMapping("/work-orders/{id}/restore-chain")
    public RestoreChainView restoreChain(@PathVariable long id) {
        return workOrders.restoreChain(id);
    }

    /** 批量评估特级工单发电车升级，返回本次新触发的工单ID。 */
    @PostMapping("/escalations/check")
    public Map<String, List<Long>> checkEscalations() {
        return Map.of("triggered", escalation.evaluateAll());
    }
}
