package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 例行跑道巡检计划与航班日历 API：
 * 日历发布/影响查询，计划发布/开始/检查回报/完成回报/详情。
 */
@RestController
@RequestMapping("/api")
public class RoutineController {

    private final RoutineService service;

    public RoutineController(RoutineService service) {
        this.service = service;
    }

    /** 发布新航班日历版本（仅追加），成功后未开始计划置待重审。requestId 幂等。 */
    @PostMapping("/flight-calendar/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarPublishResponse publishCalendar(@Valid @RequestBody CalendarPublishRequest request) {
        return service.publishCalendar(request);
    }

    /** 日历影响查询：版本占用窗与受影响例行计划。 */
    @GetMapping("/flight-calendar/versions/{version}/impact")
    public CalendarImpactResponse calendarImpact(@PathVariable int version) {
        return service.getCalendarImpact(version);
    }

    /** 例行计划申请发布：冻结日历版本与冲突证据。requestId 幂等。 */
    @PostMapping("/routine-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse publishPlan(@Valid @RequestBody PlanPublishRequest request) {
        return service.publishPlan(request);
    }

    /** 例行计划详情。 */
    @GetMapping("/routine-plans/{planId}")
    public PlanResponse getPlan(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    /** 按跑道列出例行计划。 */
    @GetMapping("/routine-plans")
    public List<PlanResponse> listPlans(@RequestParam String runwayId) {
        return service.listPlans(runwayId);
    }

    /** 例行巡检开始：重新确认日历版本且无进行中紧急关闭。expectedVersion 乐观锁。 */
    @PostMapping("/routine-plans/{planId}/start")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse startPlan(@PathVariable long planId, @Valid @RequestBody PlanStartRequest request) {
        return service.startPlan(planId, request);
    }

    /** 例行检查项回报；被抢占后仅登记迟到，不开放跑道。requestId 幂等。 */
    @PostMapping("/routine-plans/{planId}/check-reports")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanCheckReportResponse reportChecks(@PathVariable long planId,
                                                @Valid @RequestBody PlanCheckReportRequest request) {
        return service.reportChecks(planId, request);
    }

    /** 例行巡检完成回报：正常完成只关闭计划；被抢占后仅登记迟到。requestId 幂等。 */
    @PostMapping("/routine-plans/{planId}/completion")
    public PlanCompletionResponse completePlan(@PathVariable long planId,
                                               @Valid @RequestBody PlanCompletionRequest request) {
        return service.completePlan(planId, request);
    }
}
