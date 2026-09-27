package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 例行跑道巡检计划与航班日历 API：计划申请发布、开始、检查回报、完成回报、重审与日历影响查询。
 */
@RestController
@RequestMapping("/api")
public class RoutinePlanController {

    private final RoutinePlanService service;

    public RoutinePlanController(RoutinePlanService service) {
        this.service = service;
    }

    /**
     * 发布新航班日历版本：冻结占用窗集合，尚未开始计划转待重审；requestId 幂等。
     */
    @PostMapping("/flight-calendars/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarPublishResponse publishCalendar(@Valid @RequestBody CalendarPublishRequest request) {
        return service.publishCalendar(request);
    }

    /**
     * 日历影响查询：当前日历版本下受影响（冻结版本落后、尚未开始）的计划。
     */
    @GetMapping("/flight-calendars/impact")
    public CalendarImpactResponse calendarImpact() {
        return service.getCalendarImpact();
    }

    /**
     * 例行计划申请发布：校验窗口、预计时长与航班占用冲突，冻结日历版本与冲突证据；requestId 幂等。
     */
    @PostMapping("/inspection-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse publishPlan(@Valid @RequestBody PlanPublishRequest request) {
        return service.publishPlan(request);
    }

    /**
     * 计划详情：状态、版本、冻结证据、已保存检查项与完成回报（可复算）。
     */
    @GetMapping("/inspection-plans/{planId}")
    public PlanResponse getPlan(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    /**
     * 例行巡检开始：重新确认日历版本且跑道无进行中紧急关闭事件；requestId 幂等。
     */
    @PostMapping("/inspection-plans/{planId}/start")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanStartResponse startPlan(@PathVariable long planId,
                                       @Valid @RequestBody PlanStartRequest request) {
        return service.startPlan(planId, request);
    }

    /**
     * 检查项回报：逐项保存；requestId 幂等。
     */
    @PostMapping("/inspection-plans/{planId}/checks")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanCheckResponse reportCheck(@PathVariable long planId,
                                         @Valid @RequestBody PlanCheckItemRequest request) {
        return service.reportCheck(planId, request);
    }

    /**
     * 例行完成回报：正常完成仅关闭计划；紧急中止后只登记迟到，不开放跑道；requestId 幂等。
     */
    @PostMapping("/inspection-plans/{planId}/completion")
    public PlanCompleteResponse completePlan(@PathVariable long planId,
                                             @Valid @RequestBody PlanCompleteRequest request) {
        return service.completePlan(planId, request);
    }

    /**
     * 待重审计划重新审查：按当前日历版本重新校验并冻结；requestId 幂等。
     */
    @PostMapping("/inspection-plans/{planId}/review")
    public PlanResponse reviewPlan(@PathVariable long planId,
                                   @Valid @RequestBody PlanReviewRequest request) {
        return service.reviewPlan(planId, request);
    }
}
