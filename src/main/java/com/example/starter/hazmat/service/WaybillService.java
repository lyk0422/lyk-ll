package com.example.starter.hazmat.service;

import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.error.BusinessException;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.error.NotFoundException;
import com.example.starter.hazmat.model.RestrictionWindow;
import com.example.starter.hazmat.model.RouteInfo;
import com.example.starter.hazmat.model.RouteWaypoint;
import com.example.starter.hazmat.model.Waybill;
import com.example.starter.hazmat.model.WaybillWaypoint;
import com.example.starter.hazmat.repo.RouteRepository;
import com.example.starter.hazmat.repo.VehicleFreezeRepository;
import com.example.starter.hazmat.repo.WaybillRepository;
import com.example.starter.hazmat.repo.WaybillWaypointRepository;
import com.example.starter.hazmat.web.WaybillView;
import com.example.starter.hazmat.web.WaypointView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运单生命周期服务：申请 -&gt; 路线核准 -&gt; 发运 -&gt; 在途打卡 -&gt; 到达核销。
 * 申请以 requestId 幂等，指纹覆盖 requestId 与全部业务字段。
 */
@Service
public class WaybillService {

    private final WaybillRepository waybills;
    private final WaybillWaypointRepository waypoints;
    private final RouteRepository routes;
    private final VehicleFreezeRepository freezes;
    private final VehicleLocks locks;
    private final Clock clock;

    public WaybillService(WaybillRepository waybills, WaybillWaypointRepository waypoints,
                          RouteRepository routes, VehicleFreezeRepository freezes,
                          VehicleLocks locks, Clock clock) {
        this.waybills = waybills;
        this.waypoints = waypoints;
        this.routes = routes;
        this.freezes = freezes;
        this.locks = locks;
        this.clock = clock;
    }

    /**
     * 运输申请：类别/路线/管制的核准前校验不在此阶段执行，但车辆冻结与单车唯一在途立即拦截。
     * 同键同参返回首次结果，同键异参返回 409。
     */
    @Transactional
    public WaybillView apply(String requestId, String vehicleCode, int hazmatLevel,
                             long routeId, LocalDateTime plannedDeparture) {
        String fingerprint = Fingerprints.of(
                "APPLY", requestId, vehicleCode, hazmatLevel, routeId, plannedDeparture);
        Waybill existing = waybills.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }
        RouteInfo route = routes.findById(routeId)
                .orElseThrow(() -> new NotFoundException("路线不存在: " + routeId));
        return locks.execute(vehicleCode, () -> {
            if (freezes.isFrozen(vehicleCode)) {
                throw new BusinessException("车辆已被失联冻结，禁止新申请: " + vehicleCode);
            }
            if (!waybills.findActiveByVehicle(vehicleCode).isEmpty()) {
                throw new BusinessException("车辆存在执行中运单，同一车辆同时刻只允许一个执行中运单: "
                        + vehicleCode);
            }
            long id = waybills.insert(requestId, fingerprint, vehicleCode, hazmatLevel,
                    route.id(), plannedDeparture, LocalDateTime.now(clock));
            return getView(id);
        });
    }

    private WaybillView replayOrConflict(Waybill existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("requestId 已存在但业务参数不一致: " + existing.requestId());
        }
        return getView(existing.id());
    }

    /**
     * 路线核准：危化品类别等级须小于等于路线通行等级；各途经点计划通过时刻不得落入管制时段
     * （半开区间 [start, end)，恰在 end 边界视为不受管制）。
     * 拒绝时将运单置为 REJECTED 并提交（可复算），随后抛出 422。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public WaybillView approve(long waybillId) {
        Waybill waybill = requireWaybill(waybillId);
        if (waybill.status() != WaybillStatus.PENDING) {
            if (waybill.status() == WaybillStatus.REJECTED) {
                throw new BusinessException("运单已被拒绝核准: " + waybillId);
            }
            return getView(waybillId);
        }
        RouteInfo route = routes.findById(waybill.routeId())
                .orElseThrow(() -> new NotFoundException("路线不存在: " + waybill.routeId()));
        if (waybill.hazmatLevel() > route.passLevel()) {
            waybills.transition(waybillId, WaybillStatus.PENDING, WaybillStatus.REJECTED,
                    LocalDateTime.now(clock), false);
            throw new BusinessException(String.format(
                    "危化品等级 %d 高于路线通行等级 %d，禁行", waybill.hazmatLevel(),
                    route.passLevel()));
        }
        List<RouteWaypoint> template = routes.findWaypoints(route.id());
        List<RestrictionWindow> restrictions = routes.findRestrictions(route.id());
        for (RouteWaypoint wp : template) {
            LocalDateTime planned = waybill.plannedDeparture().plusMinutes(wp.offsetMinutes());
            for (RestrictionWindow window : restrictions) {
                if (window.contains(planned)) {
                    waybills.transition(waybillId, WaybillStatus.PENDING, WaybillStatus.REJECTED,
                            LocalDateTime.now(clock), false);
                    throw new BusinessException(String.format(
                            "途经点 %s 计划通过时刻 %s 落入管制时段 [%s, %s)，禁行",
                            wp.name(), planned, window.startTime(), window.endTime()));
                }
            }
        }
        int rows = waybills.transition(waybillId, WaybillStatus.PENDING, WaybillStatus.APPROVED,
                LocalDateTime.now(clock), false);
        if (rows == 0) {
            throw new ConflictException("核准并发冲突，运单状态已变化: " + waybillId);
        }
        return getView(waybillId);
    }

    /**
     * 发运：核准通过后进入在途，并按路线模板生成途经点实例，计划通过时刻 = 计划发运 + 偏移。
     */
    @Transactional
    public WaybillView depart(long waybillId) {
        Waybill waybill = requireWaybill(waybillId);
        if (waybill.status() == WaybillStatus.IN_TRANSIT
                || waybill.status() == WaybillStatus.CLOSED
                || waybill.status() == WaybillStatus.LOST) {
            return getView(waybillId);
        }
        if (waybill.status() != WaybillStatus.APPROVED) {
            throw new BusinessException("仅已核准运单可以发运，当前状态: " + waybill.status());
        }
        List<RouteWaypoint> template = routes.findWaypoints(waybill.routeId());
        int rows = waybills.transition(waybillId, WaybillStatus.APPROVED,
                WaybillStatus.IN_TRANSIT, LocalDateTime.now(clock), false);
        if (rows == 0) {
            throw new ConflictException("发运并发冲突，运单状态已变化: " + waybillId);
        }
        for (RouteWaypoint wp : template) {
            waypoints.insert(waybillId, 1, wp.seq(),
                    waybill.plannedDeparture().plusMinutes(wp.offsetMinutes()));
        }
        return getView(waybillId);
    }

    /**
     * 到达核销：IN_TRANSIT -&gt; CLOSED 原子迁移，与失联冻结竞争唯一终态；
     * CLOSED 为终态，重复核销返回幂等成功；LOST 后核销返回 409。
     */
    @Transactional
    public WaybillView close(long waybillId) {
        Waybill waybill = requireWaybill(waybillId);
        if (waybill.status() == WaybillStatus.CLOSED) {
            return getView(waybillId);
        }
        if (waybill.status() == WaybillStatus.LOST) {
            throw new ConflictException("运单已失联冻结，不得核销: " + waybillId);
        }
        int rows = waybills.transition(waybillId, WaybillStatus.IN_TRANSIT,
                WaybillStatus.CLOSED, LocalDateTime.now(clock), true);
        if (rows == 0) {
            Waybill now = requireWaybill(waybillId);
            if (now.status() == WaybillStatus.CLOSED) {
                return getView(waybillId);
            }
            throw new ConflictException("核销与终态变更并发冲突，当前状态: " + now.status());
        }
        return getView(waybillId);
    }

    @Transactional(readOnly = true)
    public WaybillView getView(long waybillId) {
        Waybill waybill = requireWaybill(waybillId);
        List<WaypointView> wpViews = waypoints.findCurrentByWaybill(waybillId,
                waybill.routeGeneration()).stream().map(WaypointView::of).toList();
        return WaybillView.of(waybill, wpViews);
    }

    private Waybill requireWaybill(long waybillId) {
        return waybills.findById(waybillId)
                .orElseThrow(() -> new NotFoundException("运单不存在: " + waybillId));
    }
}
