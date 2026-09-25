package com.example.starter.hazmat.service;

import com.example.starter.hazmat.domain.RerouteStatus;
import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.error.BusinessException;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.error.NotFoundException;
import com.example.starter.hazmat.model.RestrictionWindow;
import com.example.starter.hazmat.model.RouteInfo;
import com.example.starter.hazmat.model.RouteWaypoint;
import com.example.starter.hazmat.model.Waybill;
import com.example.starter.hazmat.repo.RerouteRepository;
import com.example.starter.hazmat.repo.RerouteRepository.RerouteRow;
import com.example.starter.hazmat.repo.RouteRepository;
import com.example.starter.hazmat.repo.WaybillRepository;
import com.example.starter.hazmat.repo.WaybillWaypointRepository;
import com.example.starter.hazmat.web.RerouteView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 紧急改线服务：须申请新路线核准；核准通过前原路线打卡继续有效；
 * 改线生效与原路线途经点终止在同一事务内完成。
 */
@Service
public class RerouteService {

    private final RerouteRepository reroutes;
    private final WaybillRepository waybills;
    private final WaybillWaypointRepository waypoints;
    private final RouteRepository routes;
    private final Clock clock;

    public RerouteService(RerouteRepository reroutes, WaybillRepository waybills,
                          WaybillWaypointRepository waypoints, RouteRepository routes,
                          Clock clock) {
        this.reroutes = reroutes;
        this.waybills = waybills;
        this.waypoints = waypoints;
        this.routes = routes;
        this.clock = clock;
    }

    /**
     * 改线申请：requestId 幂等；仅在途运单可申请；新路线须满足类别等级匹配。
     */
    @Transactional
    public RerouteView apply(String requestId, long waybillId, long newRouteId) {
        String fingerprint = Fingerprints.of("REROUTE", requestId, waybillId, newRouteId);
        RerouteRow existing = reroutes.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已存在但业务参数不一致: " + requestId);
            }
            return viewOf(existing);
        }
        Waybill waybill = requireWaybill(waybillId);
        if (waybill.status() != WaybillStatus.IN_TRANSIT) {
            throw new BusinessException("仅在途运单可以申请改线，当前状态: " + waybill.status());
        }
        RouteInfo route = routes.findById(newRouteId)
                .orElseThrow(() -> new NotFoundException("路线不存在: " + newRouteId));
        if (waybill.hazmatLevel() > route.passLevel()) {
            throw new BusinessException(String.format(
                    "危化品等级 %d 高于新路线通行等级 %d，禁行", waybill.hazmatLevel(),
                    route.passLevel()));
        }
        long id = reroutes.insert(requestId, fingerprint, waybillId, newRouteId,
                LocalDateTime.now(clock));
        return viewOf(reroutes.findById(id).orElseThrow());
    }

    /**
     * 改线核准：校验新路线管制时段；通过后在同一事务内终止原路线未打卡途经点、
     * 切换运单路线并生成新路线途经点（计划通过时刻 = 核准时刻 + 偏移）。
     * 拒绝时将申请置为 REJECTED 并提交，随后抛出 422。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public RerouteView approve(long rerouteId) {
        RerouteRow row = reroutes.findById(rerouteId)
                .orElseThrow(() -> new NotFoundException("改线申请不存在: " + rerouteId));
        if (row.status() == RerouteStatus.APPROVED) {
            return viewOf(row);
        }
        if (row.status() == RerouteStatus.REJECTED) {
            throw new BusinessException("改线申请已被拒绝: " + rerouteId);
        }
        Waybill waybill = requireWaybill(row.waybillId());
        if (waybill.status() != WaybillStatus.IN_TRANSIT) {
            throw new ConflictException("运单已不在途，改线无法生效，当前状态: " + waybill.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        RouteInfo route = routes.findById(row.newRouteId())
                .orElseThrow(() -> new NotFoundException("路线不存在: " + row.newRouteId()));
        List<RouteWaypoint> template = routes.findWaypoints(route.id());
        List<RestrictionWindow> restrictions = routes.findRestrictions(route.id());
        for (RouteWaypoint wp : template) {
            LocalDateTime planned = now.plusMinutes(wp.offsetMinutes());
            for (RestrictionWindow window : restrictions) {
                if (window.contains(planned)) {
                    reroutes.transition(rerouteId, RerouteStatus.REJECTED, now);
                    throw new BusinessException(String.format(
                            "新路线途经点 %s 计划通过时刻 %s 落入管制时段 [%s, %s)，改线拒绝",
                            wp.name(), planned, window.startTime(), window.endTime()));
                }
            }
        }
        int approved = reroutes.transition(rerouteId, RerouteStatus.APPROVED, now);
        if (approved == 0) {
            throw new ConflictException("改线核准并发冲突: " + rerouteId);
        }
        int oldGeneration = waybill.routeGeneration();
        int switched = waybills.switchRoute(waybill.id(), route.id(), oldGeneration + 1, now);
        if (switched == 0) {
            throw new ConflictException("运单状态并发变化，改线终止: " + waybill.id());
        }
        waypoints.cancelOpenWaypoints(waybill.id(), oldGeneration);
        for (RouteWaypoint wp : template) {
            waypoints.insert(waybill.id(), oldGeneration + 1, wp.seq(),
                    now.plusMinutes(wp.offsetMinutes()));
        }
        return viewOf(reroutes.findById(rerouteId).orElseThrow());
    }

    private Waybill requireWaybill(long waybillId) {
        return waybills.findById(waybillId)
                .orElseThrow(() -> new NotFoundException("运单不存在: " + waybillId));
    }

    private RerouteView viewOf(RerouteRow row) {
        return new RerouteView(row.id(), row.requestId(), row.waybillId(), row.newRouteId(),
                row.status(), null);
    }
}
