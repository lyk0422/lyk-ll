package com.example.starter.cems;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.cems.CemsTypes.ArchiveOutletRequest;
import com.example.starter.cems.CemsTypes.ArchiveResult;
import com.example.starter.cems.CemsTypes.ConfirmOrderRequest;
import com.example.starter.cems.CemsTypes.DailyEvalRequest;
import com.example.starter.cems.CemsTypes.DailyEvalResult;
import com.example.starter.cems.CemsTypes.EscalateSweepRequest;
import com.example.starter.cems.CemsTypes.GapCheckRequest;
import com.example.starter.cems.CemsTypes.GapResult;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingResult;
import com.example.starter.cems.CemsTypes.OrderView;
import com.example.starter.cems.CemsTypes.OutletView;
import com.example.starter.cems.CemsTypes.Receipt;
import com.example.starter.cems.CemsTypes.RecomputeRequest;
import com.example.starter.cems.CemsTypes.RecomputeResult;
import com.example.starter.cems.CemsTypes.RegisterOutletRequest;
import com.example.starter.cems.CemsTypes.ReleaseRestrictionRequest;
import com.example.starter.cems.CemsTypes.ReleaseResult;

/**
 * 污水排放在线监测与限排联动 HTTP 入口。
 * 标记（日评价）、限排（确认/解除）与归档均以 requestId 幂等。
 */
@RestController
@RequestMapping("/api/cems")
public class CemsController {

    private final CemsService service;

    public CemsController(CemsService service) {
        this.service = service;
    }

    /** 许可登记。 */
    @PostMapping("/outlets")
    public OutletView register(@RequestBody RegisterOutletRequest req) {
        return service.registerOutlet(req);
    }

    @GetMapping("/outlets/{code}")
    public OutletView outlet(@PathVariable String code) {
        return service.getOutlet(code);
    }

    /** 日均值评价：超过限值生成超标标记，恰等限值判达标。 */
    @PostMapping("/daily-eval")
    public DailyEvalResult dailyEval(@RequestBody DailyEvalRequest req) {
        return service.evaluateDaily(req);
    }

    /** 小时均值上报：连续 3 次小时超标自动下达限排令。 */
    @PostMapping("/hourly-readings")
    public HourlyReadingResult hourlyReading(@RequestBody HourlyReadingRequest req) {
        return service.reportHourly(req);
    }

    /** 连续缺失检查：严格大于 2 小时督办。 */
    @PostMapping("/gaps/check")
    public GapResult gapCheck(@RequestBody GapCheckRequest req) {
        return service.checkGap(req);
    }

    /** 限排令确认执行（2 小时内），生效同时冻结排放总量基数。 */
    @PostMapping("/orders/confirm")
    public Receipt confirm(@RequestBody ConfirmOrderRequest req) {
        return service.confirmOrder(req);
    }

    /** 超时升级巡检：恰满 2 小时未确认升级并联动限产。 */
    @PostMapping("/orders/escalate-sweep")
    public List<OrderView> escalate(@RequestBody EscalateSweepRequest req) {
        return service.escalateSweep(req.outletCode());
    }

    @GetMapping("/orders/{orderNo}")
    public OrderView order(@PathVariable String orderNo) {
        return service.getOrder(orderNo);
    }

    /** 复测合格解除：与限排令关闭同一事务。 */
    @PostMapping("/orders/release")
    public ReleaseResult release(@RequestBody ReleaseRestrictionRequest req) {
        return service.release(req);
    }

    /** 排口归档（终态），重复归档幂等成功。 */
    @PostMapping("/outlets/archive")
    public ArchiveResult archive(@RequestBody ArchiveOutletRequest req) {
        return service.archive(req);
    }

    /** 复算标记链、缺失督办与限排记录。 */
    @PostMapping("/recompute")
    public RecomputeResult recompute(@RequestBody RecomputeRequest req) {
        return service.recompute(req);
    }

    @GetMapping("/outlets/{code}/events")
    public List<Map<String, Object>> events(@PathVariable String code) {
        return service.getEvents(code);
    }
}
