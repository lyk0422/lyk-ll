package com.example.starter.web;

import com.example.starter.service.AssessmentService;
import com.example.starter.service.CheckInService;
import com.example.starter.service.RouteService;
import com.example.starter.web.dto.RouteDtos.AssessmentRequest;
import com.example.starter.web.dto.RouteDtos.AssessmentResponse;
import com.example.starter.web.dto.RouteDtos.CheckInRequest;
import com.example.starter.web.dto.RouteDtos.CheckInResponse;
import com.example.starter.web.dto.RouteDtos.CreateRouteRequest;
import com.example.starter.web.dto.RouteDtos.MissedBoardingRequest;
import com.example.starter.web.dto.RouteDtos.MissedBoardingResponse;
import com.example.starter.web.dto.RouteDtos.RouteResponse;
import com.example.starter.web.dto.RouteDtos.StartRouteRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线路申请、评估、执行与打卡接口。
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteService routeService;
    private final AssessmentService assessmentService;
    private final CheckInService checkInService;

    public RouteController(RouteService routeService, AssessmentService assessmentService,
                           CheckInService checkInService) {
        this.routeService = routeService;
        this.assessmentService = assessmentService;
        this.checkInService = checkInService;
    }

    /**
     * 创建线路申请。
     */
    @PostMapping
    public RouteResponse createRoute(@Valid @RequestBody CreateRouteRequest request) {
        return routeService.createRoute(request);
    }

    /**
     * 查询线路。
     */
    @GetMapping("/{routeId}")
    public RouteResponse getRoute(@PathVariable long routeId) {
        return routeService.getRoute(routeId);
    }

    /**
     * 安全评估；通过则生成 1 年有效期运行资格。
     */
    @PostMapping("/{routeId}/assessments")
    public AssessmentResponse assess(@PathVariable long routeId,
                                     @Valid @RequestBody AssessmentRequest request) {
        return assessmentService.assess(routeId, request.requestId());
    }

    /**
     * 启动线路执行；同一车辆同一时刻仅允许一条执行中线路。
     */
    @PostMapping("/{routeId}/start")
    public RouteResponse start(@PathVariable long routeId,
                               @Valid @RequestBody StartRouteRequest request) {
        return routeService.startRoute(routeId, request.requestId());
    }

    /**
     * 学生上下车打卡，按学生+日期+类型去重。
     */
    @PostMapping("/{routeId}/check-ins")
    public CheckInResponse checkIn(@PathVariable long routeId,
                                   @Valid @RequestBody CheckInRequest request) {
        return checkInService.checkIn(routeId, request.studentId(), request.checkType(),
                request.requestId());
    }

    /**
     * 生成当日漏乘名单并实时生成合成通知记录。
     */
    @PostMapping("/{routeId}/missed-boarding")
    public MissedBoardingResponse missedBoarding(@PathVariable long routeId,
                                               @Valid @RequestBody MissedBoardingRequest request) {
        return checkInService.generateMissedBoarding(routeId, request.requestId());
    }
}
