package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 例行跑道巡检窗口编排 API：计划申请发布、开始、检查回报、日历变更与影响查询。
 */
@RestController
@RequestMapping("/api")
public class InspectionPlanController {

    private final InspectionPlanService service;

    public InspectionPlanController(InspectionPlanService service) {
        this.service = service;
    }

    /**
     * 计划申请发布：冻结日历版本与冲突证据；requestId 幂等。
     */
    @PostMapping("/inspection-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse publish(@Valid @RequestBody PlanPublishRequest request) {
        return service.publish(request);
    }

    /**
     * 计划详情。
     */
    @GetMapping("/inspection-plans/{planId}")
    public PlanResponse detail(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    /**
     * 开始巡检：重新确认日历版本，跑道须无进行中紧急关闭事件。
     */
    @PostMapping("/inspection-plans/{planId}/start")
    public PlanResponse start(@PathVariable long planId,
                              @Valid @RequestBody PlanStartRequest request) {
        return service.start(planId, request);
    }

    /**
     * 检查回报：完结仅关闭计划；被抢占后仅登记迟到，不开放跑道。
     */
    @PostMapping("/inspection-plans/{planId}/check-reports")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckReportResponse reportCheck(@PathVariable long planId,
                                           @Valid @RequestBody CheckReportRequest request) {
        return service.reportCheck(planId, request);
    }

    /**
     * 航班日历变更：版本加一，未开始计划转待重审。
     */
    @PostMapping("/flight-calendars/{runwayId}/changes")
    public CalendarChangeResponse changeCalendar(@PathVariable String runwayId,
                                                 @Valid @RequestBody CalendarChangeRequest request) {
        return service.changeCalendar(runwayId, request);
    }

    /**
     * 日历影响查询：冻结版本落后于当前版本的非终态计划。
     */
    @GetMapping("/flight-calendars/{runwayId}/impact")
    public CalendarImpactResponse calendarImpact(@PathVariable String runwayId) {
        return service.calendarImpact(runwayId);
    }
}
