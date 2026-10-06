package com.example.starter.service;

import com.example.starter.domain.RouteStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.repo.QualificationRepository;
import com.example.starter.repo.RouteRepository;
import com.example.starter.time.TimeProvider;
import com.example.starter.web.dto.RouteDtos.CreateRouteRequest;
import com.example.starter.web.dto.RouteDtos.RouteResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 线路申请与执行服务。
 */
@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final QualificationRepository qualificationRepository;
    private final IdempotencyService idempotencyService;
    private final TimeProvider timeProvider;

    public RouteService(RouteRepository routeRepository,
                        QualificationRepository qualificationRepository,
                        IdempotencyService idempotencyService,
                        TimeProvider timeProvider) {
        this.routeRepository = routeRepository;
        this.qualificationRepository = qualificationRepository;
        this.idempotencyService = idempotencyService;
        this.timeProvider = timeProvider;
    }

    /**
     * 创建线路申请（幂等）。
     */
    @Transactional
    public RouteResponse createRoute(CreateRouteRequest request) {
        String fingerprint = idempotencyService.fingerprint(request.requestId(),
                request.routeCode(), request.name(), request.vehicleId(),
                request.stationSpacingM(), request.roadGrade(), request.requiredSeats());
        return idempotencyService.execute("POST /api/routes", request.requestId(), fingerprint,
                RouteResponse.class, () -> doCreate(request));
    }

    private RouteResponse doCreate(CreateRouteRequest request) {
        if (!routeRepository.vehicleExists(request.vehicleId())) {
            throw BusinessException.unprocessable("VEHICLE_NOT_FOUND",
                    "候选车辆不存在: " + request.vehicleId());
        }
        long id = routeRepository.insert(request.routeCode(), request.name(), request.vehicleId(),
                request.stationSpacingM(), request.roadGrade(), request.requiredSeats());
        return toResponse(routeRepository.findById(id).orElseThrow());
    }

    /**
     * 查询线路。
     */
    @Transactional(readOnly = true)
    public RouteResponse getRoute(long routeId) {
        return toResponse(routeRepository.findById(routeId)
                .orElseThrow(() -> BusinessException.notFound("线路不存在: " + routeId)));
    }

    /**
     * 启动线路执行（幂等）。要求评估通过、资格在有效期内，且同一车辆无其他执行中线路。
     */
    @Transactional
    public RouteResponse startRoute(long routeId, String requestId) {
        String fingerprint = idempotencyService.fingerprint(requestId, routeId);
        return idempotencyService.execute("POST /api/routes/{id}/start", requestId, fingerprint,
                RouteResponse.class, () -> doStart(routeId));
    }

    private RouteResponse doStart(long routeId) {
        RouteRepository.RouteRow route = routeRepository.findById(routeId)
                .orElseThrow(() -> BusinessException.notFound("线路不存在: " + routeId));
        if (!RouteStatus.QUALIFIED.name().equals(route.status())) {
            throw BusinessException.conflict("ROUTE_NOT_QUALIFIED",
                    "线路当前状态不允许启动执行: " + route.status());
        }
        qualificationRepository.findActiveByRoute(routeId, timeProvider.today())
                .orElseThrow(() -> BusinessException.unprocessable("QUALIFICATION_INACTIVE",
                        "线路无有效运行资格"));
        routeRepository.lockVehicle(route.vehicleId());
        if (routeRepository.countInProgressByVehicle(route.vehicleId(), routeId) > 0) {
            throw BusinessException.conflict("VEHICLE_BUSY",
                    "车辆已绑定其他执行中线路: " + route.vehicleId());
        }
        routeRepository.updateStatus(routeId, RouteStatus.IN_PROGRESS);
        return toResponse(routeRepository.findById(routeId).orElseThrow());
    }

    /**
     * 线路记录转响应。
     */
    public static RouteResponse toResponse(RouteRepository.RouteRow row) {
        return new RouteResponse(row.id(), row.routeCode(), row.name(), row.vehicleId(),
                row.stationSpacingM(), row.roadGrade(), row.requiredSeats(), row.status());
    }
}
