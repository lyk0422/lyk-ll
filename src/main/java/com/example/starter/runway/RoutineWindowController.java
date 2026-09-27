package com.example.starter.runway;

import com.example.starter.runway.dto.CalendarImpactResponse;
import com.example.starter.runway.dto.CalendarPublishRequest;
import com.example.starter.runway.dto.CalendarPublishResponse;
import com.example.starter.runway.dto.EmergencyEndRequest;
import com.example.starter.runway.dto.EmergencyEndResponse;
import com.example.starter.runway.dto.EmergencyReportRequest;
import com.example.starter.runway.dto.EmergencyReportResponse;
import com.example.starter.runway.dto.EmergencyView;
import com.example.starter.runway.dto.PlanCheckReportRequest;
import com.example.starter.runway.dto.PlanCheckReportResponse;
import com.example.starter.runway.dto.PlanPublishRequest;
import com.example.starter.runway.dto.PlanPublishResponse;
import com.example.starter.runway.dto.PlanReviewRequest;
import com.example.starter.runway.dto.PlanStartRequest;
import com.example.starter.runway.dto.PlanStartResponse;
import com.example.starter.runway.dto.RoutinePlanView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 例行巡检窗口编排与紧急关闭抢占 API：日历发布/影响查询、计划发布/开始/重审/检查回报、紧急接报/结束。
 */
@RestController
@RequestMapping("/api")
public class RoutineWindowController {

    private final RoutineWindowService service;

    public RoutineWindowController(RoutineWindowService service) {
        this.service = service;
    }

    /**
     * 发布合成航班日历版本（版本从1开始），同事务使未开始旧版本计划待重审；requestId 幂等。
     */
    @PostMapping("/flight-calendars")
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarPublishResponse publishCalendar(@Valid @RequestBody CalendarPublishRequest request) {
        return service.publishCalendar(request);
    }

    /**
     * 日历影响查询：缺省按最新版本计算巡检区间与航班占用窗的正长度相交证据。
     */
    @GetMapping("/flight-calendars/impact")
    public CalendarImpactResponse calendarImpact(
            @RequestParam String runwayId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant windowStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant windowEnd,
            @RequestParam int durationMinutes,
            @RequestParam(required = false) Integer calendarVersion) {
        return service.calendarImpact(runwayId, windowStart, windowEnd, durationMinutes, calendarVersion);
    }

    /**
     * 发布例行巡检计划：冻结日历版本与冲突证据；requestId 幂等。
     */
    @PostMapping("/routine-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanPublishResponse publishPlan(@Valid @RequestBody PlanPublishRequest request) {
        return service.publishPlan(request);
    }

    /**
     * 按跑道列出例行计划（含终态，按窗口排序）。
     */
    @GetMapping("/routine-plans")
    public List<RoutinePlanView> listPlans(@RequestParam String runwayId) {
        return service.listPlans(runwayId);
    }

    /**
     * 计划详情：冻结证据、检查项状态、当前版本。
     */
    @GetMapping("/routine-plans/{planId}")
    public RoutinePlanView getPlan(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    /**
     * 开始巡检：重新确认日历版本且跑道无进行中紧急关闭；requestId 幂等。
     */
    @PostMapping("/routine-plans/{planId}/start")
    public PlanStartResponse startPlan(@PathVariable long planId,
                                       @Valid @RequestBody PlanStartRequest request) {
        return service.startPlan(planId, request);
    }

    /**
     * 待重审计划按最新日历版本重新审核；requestId 幂等。
     */
    @PostMapping("/routine-plans/{planId}/review")
    public PlanPublishResponse reviewPlan(@PathVariable long planId,
                                          @Valid @RequestBody PlanReviewRequest request) {
        return service.reviewPlan(planId, request);
    }

    /**
     * 检查项完成回报：全部完成正常关闭计划；中止后的新回报只登记迟到，不开放跑道；requestId 幂等。
     */
    @PostMapping("/routine-plans/{planId}/checks")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanCheckReportResponse reportCheck(@PathVariable long planId,
                                               @Valid @RequestBody PlanCheckReportRequest request) {
        return service.reportCheck(planId, request);
    }

    /**
     * 接报紧急关闭：同事务取消未开始计划、中止进行中计划；requestId 幂等。
     */
    @PostMapping("/emergencies")
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyReportResponse reportEmergency(@Valid @RequestBody EmergencyReportRequest request) {
        return service.reportEmergency(request);
    }

    /**
     * 紧急事件详情。
     */
    @GetMapping("/emergencies/{emergencyId}")
    public EmergencyView getEmergency(@PathVariable long emergencyId) {
        return service.getEmergency(emergencyId);
    }

    /**
     * 结束紧急事件：之后可另建新计划，不复活被抢占项；requestId 幂等。
     */
    @PostMapping("/emergencies/{emergencyId}/end")
    public EmergencyEndResponse endEmergency(@PathVariable long emergencyId,
                                             @Valid @RequestBody EmergencyEndRequest request) {
        return service.endEmergency(emergencyId, request);
    }
}
