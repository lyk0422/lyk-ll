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
import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.service.GeneratorTruckService;
import com.example.starter.service.RestoreService;
import com.example.starter.service.WorkOrderService;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.FaultReportResponse;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.MergeResponse;
import com.example.starter.web.dto.QueueEntry;
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.RestoreChainView;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.RestoreConfirmResponse;
import com.example.starter.web.dto.SealResponse;
import com.example.starter.web.dto.TruckArrivalRequest;
import com.example.starter.web.dto.TruckArrivalResponse;
import com.example.starter.web.dto.TruckLedgerEntry;
import com.example.starter.web.dto.TruckRegisterRequest;
import com.example.starter.web.dto.TruckUpdateRequest;
import com.example.starter.web.dto.TruckView;
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
    private final GeneratorTruckService trucks;
    private final GeneratorDispatchService generatorDispatches;

    public RepairController(FaultReportService faultReports, DispatchService dispatch,
            RestoreService restore, WorkOrderService workOrders, EscalationService escalation,
            GeneratorTruckService trucks, GeneratorDispatchService generatorDispatches) {
        this.faultReports = faultReports;
        this.dispatch = dispatch;
        this.restore = restore;
        this.workOrders = workOrders;
        this.escalation = escalation;
        this.trucks = trucks;
        this.generatorDispatches = generatorDispatches;
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

    /** 登记发电车（容量/服务馈线/可用状态），requestId 幂等。 */
    @PostMapping("/generator-trucks")
    public TruckView registerTruck(@Validated @RequestBody TruckRegisterRequest req) {
        return trucks.register(req);
    }

    /** 变更发电车容量/可用状态，携带期望版本。 */
    @PostMapping("/generator-trucks/{id}")
    public TruckView updateTruck(@PathVariable long id, @Validated @RequestBody TruckUpdateRequest req) {
        return trucks.update(id, req);
    }

    /** 容量及车辆占用台账。 */
    @GetMapping("/generator-trucks/ledger")
    public List<TruckLedgerEntry> truckLedger(@RequestParam(required = false) String feederId) {
        return trucks.ledger(feederId);
    }

    /** 创建发电车组合调度单：冻结负荷清单与工单版本并原子占用全车，requestId 幂等。 */
    @PostMapping("/generator-dispatches")
    public GeneratorDispatchView createGeneratorDispatch(
            @Validated @RequestBody GeneratorDispatchRequest req) {
        return generatorDispatches.create(req);
    }

    /** 发电车到场确认：逐车唯一记录，全部到场才供电；迟到只记事件。 */
    @PostMapping("/generator-dispatches/{id}/arrivals")
    public TruckArrivalResponse arrive(@PathVariable long id,
            @Validated @RequestBody TruckArrivalRequest req) {
        return generatorDispatches.arrive(id, req);
    }

    /** 整体改派：保留已到场车，其余释放换新车，全方案原子切换。 */
    @PostMapping("/generator-dispatches/{id}/reassign")
    public GeneratorDispatchView reassign(@PathVariable long id,
            @Validated @RequestBody ReassignRequest req) {
        return generatorDispatches.reassign(id, req);
    }

    /** 调度单详情（含组合车辆、冻结负荷清单）。 */
    @GetMapping("/generator-dispatches/{id}")
    public GeneratorDispatchView generatorDispatchView(@PathVariable long id) {
        return generatorDispatches.view(id);
    }

    /** 工单的发电车调度单列表。 */
    @GetMapping("/work-orders/{id}/generator-dispatches")
    public List<GeneratorDispatchView> generatorDispatchesOfOrder(@PathVariable long id) {
        return generatorDispatches.listForOrder(id);
    }

    /** 批量评估到达超时：下发满30分钟未全到场转到达超时，返回本次新转换的调度单ID。 */
    @PostMapping("/generator-dispatches/timeout-check")
    public Map<String, List<Long>> timeoutCheck() {
        return Map.of("timedOut", generatorDispatches.timeoutCheck());
    }
}
