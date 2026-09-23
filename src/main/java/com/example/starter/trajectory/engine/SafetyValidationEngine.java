package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.RuleNumber;
import com.example.starter.trajectory.dto.FailureEvidence;
import com.example.starter.trajectory.dto.FailureItem;
import com.example.starter.trajectory.dto.ObstaclePrediction;
import com.example.starter.trajectory.dto.Point;
import com.example.starter.trajectory.dto.RoadBoundary;
import com.example.starter.trajectory.dto.RuleConfig;
import com.example.starter.trajectory.dto.TimedPoint;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.VehicleState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轨迹安全规则引擎。
 *
 * <p>四组规则相互独立，使用虚拟线程并行执行：时间连续性（R1）、动力学（R2/R3/R4）、
 * 道路边界（R5）、碰撞（R6）。每条规则（R6 细化到每个障碍物）只保留按时间顺序遇到的
 * 首次触发证据，后续重复触发不得替换。</p>
 *
 * <p>时间锚定约定：车辆初始状态锚定在首个轨迹点时间 {@code t0}，即 {@code v(t0)=initialState.speed}；
 * 到达 {@code p_i} 的分段速度 {@code v_i=|p_i-p_{i-1}|/(t_i-t_{i-1})}（i≥1）；
 * 加速度 {@code a_i=(v_i-v_{i-1})/(t_i-t_{i-1})}；曲率取连续三点外接圆曲率，触发时间记为中间点时间。
 * 非严格递增（{@code dt<=0}）的异常间隔只由 R1 记录，动力学与碰撞跳过该间隔，避免除零。</p>
 */
@Component
public class SafetyValidationEngine {

    /** 边界判定容差，单位米。 */
    static final double EPS = 1e-9;

    /**
     * 严格超界判定，附带 1e-9 相对/绝对误差以吸收双精度舍入（如曲率 1/R），
     * 即值恰好等于阈值时仍判合法，只有明确超出才失败。
     */
    private static boolean exceeds(double value, double limit) {
        return value > limit + Math.max(EPS, EPS * Math.abs(limit));
    }

    private static final Comparator<FailureItem> FAILURE_ORDER = Comparator
            .comparingInt((FailureItem f) -> RuleNumber.valueOf(ruleEnumName(f.ruleId())).getCode())
            .thenComparingDouble(FailureItem::firstTriggerTime)
            .thenComparing(f -> f.objectId() == null ? "" : f.objectId(), Comparator.naturalOrder());

    /**
     * 执行全部安全规则，失败项按规则编号、首次触发时间、对象 ID 固定排序。
     */
    public List<FailureItem> validate(RuleConfig config, ValidationRequest request) {
        double maxInterval = config.maxTimeInterval().doubleValue();
        double maxSpeed = config.maxSpeed().doubleValue();
        double maxAcceleration = config.maxAcceleration().doubleValue();
        double maxCurvature = config.maxCurvature().doubleValue();
        double roadTolerance = config.roadTolerance().doubleValue();
        double safetyDistance = config.safetyDistance().doubleValue();

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<List<FailureItem>> timeFuture =
                    CompletableFuture.supplyAsync(() -> checkTimeContinuity(maxInterval, request.trajectory()), pool);
            CompletableFuture<List<FailureItem>> dynamicsFuture =
                    CompletableFuture.supplyAsync(() -> checkDynamics(maxSpeed, maxAcceleration, maxCurvature,
                            request.initialState(), request.trajectory()), pool);
            CompletableFuture<List<FailureItem>> roadFuture =
                    CompletableFuture.supplyAsync(() -> checkRoad(roadTolerance, request.trajectory(),
                            request.roadBoundary()), pool);
            CompletableFuture<List<FailureItem>> collisionFuture =
                    CompletableFuture.supplyAsync(() -> checkCollisions(safetyDistance, request.trajectory(),
                            request.obstacles()), pool);

            List<FailureItem> failures = new ArrayList<>();
            failures.addAll(timeFuture.join());
            failures.addAll(dynamicsFuture.join());
            failures.addAll(roadFuture.join());
            failures.addAll(collisionFuture.join());
            failures.sort(FAILURE_ORDER);
            return List.copyOf(failures);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * R1：时间必须严格递增；相邻间隔 {@code <=0} 或超过最大间隔阈值都在到达后一点时触发。
     */
    private List<FailureItem> checkTimeContinuity(double maxInterval, List<TimedPoint> trajectory) {
        List<FailureItem> failures = new ArrayList<>(1);
        for (int i = 1; i < trajectory.size(); i++) {
            TimedPoint prev = trajectory.get(i - 1);
            TimedPoint cur = trajectory.get(i);
            double dt = cur.t() - prev.t();
            if (dt <= EPS || exceeds(dt, maxInterval)) {
                failures.add(FailureItem.of(RuleNumber.TIME_CONTINUITY, cur.t(), null,
                        maxInterval, dt, new FailureEvidence(List.of(prev, cur), List.of())));
                return failures;
            }
        }
        return failures;
    }

    /**
     * R2/R3/R4：速度、加速度、曲率边界；等于阈值合法，仅严格超出失败，只保留首次。
     */
    private List<FailureItem> checkDynamics(double maxSpeed, double maxAcceleration, double maxCurvature,
                                            VehicleState initial, List<TimedPoint> trajectory) {
        List<FailureItem> failures = new ArrayList<>(3);

        // R2 初始速度锚定 t0
        TimedPoint first = trajectory.get(0);
        if (exceeds(initial.speed(), maxSpeed)) {
            TimedPoint initialPoint = new TimedPoint(first.t(), initial.x(), initial.y());
            failures.add(FailureItem.of(RuleNumber.SPEED, first.t(), null,
                    maxSpeed, initial.speed(),
                    new FailureEvidence(List.of(initialPoint, first), List.of())));
        }

        Double previousSpeed = initial.speed();
        for (int i = 1; i < trajectory.size(); i++) {
            TimedPoint prev = trajectory.get(i - 1);
            TimedPoint cur = trajectory.get(i);
            double dt = cur.t() - prev.t();
            if (dt <= EPS) {
                // 非递增间隔交由 R1 处理，动力学不除零
                continue;
            }
            double speed = Geometry.distance(prev.toPoint(), cur.toPoint()) / dt;

            if (failures.stream().noneMatch(f -> RuleNumber.SPEED.name().equals(ruleEnumName(f.ruleId())))
                    && exceeds(speed, maxSpeed)) {
                failures.add(FailureItem.of(RuleNumber.SPEED, cur.t(), null,
                        maxSpeed, speed, new FailureEvidence(List.of(prev, cur), List.of())));
            }
            if (previousSpeed != null) {
                double acceleration = (speed - previousSpeed) / dt;
                if (failures.stream().noneMatch(f -> RuleNumber.ACCELERATION.name().equals(ruleEnumName(f.ruleId())))
                        && exceeds(Math.abs(acceleration), maxAcceleration)) {
                    failures.add(FailureItem.of(RuleNumber.ACCELERATION, cur.t(), null,
                            maxAcceleration, acceleration, new FailureEvidence(List.of(prev, cur), List.of())));
                }
            }
            previousSpeed = speed;

            if (i >= 2 && failures.stream().noneMatch(f -> RuleNumber.CURVATURE.name().equals(ruleEnumName(f.ruleId())))) {
                TimedPoint before = trajectory.get(i - 2);
                double dtPrev = prev.t() - before.t();
                if (dtPrev > EPS) {
                    double curvature = Geometry.curvature(before.toPoint(), prev.toPoint(), cur.toPoint());
                    if (exceeds(curvature, maxCurvature)) {
                        failures.add(FailureItem.of(RuleNumber.CURVATURE, prev.t(), null,
                                maxCurvature, curvature,
                                new FailureEvidence(List.of(before, prev, cur), List.of())));
                    }
                }
            }
        }
        return failures;
    }

    /**
     * R5：点在多边形边界上或内部合法；在外部但与边界距离不超过道路容差仍合法，
     * 严格超出容差才失败，只保留首个越界点。
     */
    private List<FailureItem> checkRoad(double roadTolerance, List<TimedPoint> trajectory, RoadBoundary boundary) {
        List<Point> polygon = boundary.polygon();
        for (TimedPoint point : trajectory) {
            Point p = point.toPoint();
            double edgeDistance = Geometry.distanceToPolygonBoundary(p, polygon);
            boolean onBoundary = edgeDistance <= EPS;
            if (onBoundary || Geometry.isInsidePolygon(p, polygon)) {
                continue;
            }
            if (exceeds(edgeDistance, roadTolerance)) {
                return List.of(FailureItem.of(RuleNumber.ROAD_BOUNDARY, point.t(), null,
                        roadTolerance, edgeDistance,
                        new FailureEvidence(List.of(point), List.of())));
            }
        }
        return List.of();
    }

    /**
     * R6：车辆分段线性运动与障碍物分段线性预测之间，在轨迹时间轴上求最早接触时刻；
     * 距离小于或等于安全距离即碰撞风险。每个障碍物最多一个失败项，取该障碍物最早触发。
     */
    private List<FailureItem> checkCollisions(double safetyDistance, List<TimedPoint> trajectory,
                                              List<ObstaclePrediction> obstacles) {
        if (obstacles == null) {
            return List.of();
        }
        List<FailureItem> failures = new ArrayList<>();
        for (ObstaclePrediction obstacle : obstacles) {
            FailureItem failure = checkOneObstacle(safetyDistance, trajectory, obstacle);
            if (failure != null) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private FailureItem checkOneObstacle(double safetyDistance, List<TimedPoint> trajectory,
                                         ObstaclePrediction obstacle) {
        List<TimedPoint> obs = obstacle.points();
        if (obs == null || obs.size() < 2 || trajectory.size() < 2) {
            return null;
        }
        double overlapStart = Math.max(trajectory.get(0).t(), obs.get(0).t());
        double overlapEnd = Math.min(trajectory.get(trajectory.size() - 1).t(), obs.get(obs.size() - 1).t());
        if (overlapStart > overlapEnd + EPS) {
            return null;
        }

        double safetySquared = safetyDistance * safetyDistance;
        for (int i = 1; i < trajectory.size(); i++) {
            TimedPoint p0 = trajectory.get(i - 1);
            TimedPoint p1 = trajectory.get(i);
            double vehicleDt = p1.t() - p0.t();
            double segStart = Math.max(p0.t(), overlapStart);
            double segEnd = Math.min(p1.t(), overlapEnd);
            // 车辆非递增间隔交由 R1 处理（避免除零）；segStart==segEnd 表示时间域在单个时刻
            // 相接，仍需在该端点时刻判定一次距离
            if (vehicleDt <= EPS || segStart > segEnd + EPS) {
                continue;
            }
            // 将障碍物预测断点合并进本车分段，子区间内相对运动为匀速直线
            List<Double> breaks = new ArrayList<>();
            breaks.add(segStart);
            for (int k = 0; k < obs.size() - 1; k++) {
                double qt = obs.get(k).t();
                if (qt > segStart + EPS && qt < segEnd - EPS) {
                    breaks.add(qt);
                }
            }
            breaks.add(segEnd);

            for (int b = 0; b < breaks.size() - 1; b++) {
                double a = breaks.get(b);
                double c = breaks.get(b + 1);
                int qi = Math.min(bracketIndex(obs, a), obs.size() - 2);
                TimedPoint q0 = obs.get(qi);
                TimedPoint q1 = obs.get(qi + 1);
                if (q1.t() - q0.t() <= EPS) {
                    continue;
                }

                double[] contact = earliestContact(p0, p1, q0, q1, a, c, safetySquared);
                if (contact != null) {
                    double contactTime = contact[0];
                    double contactDistance = contact[1];
                    return FailureItem.of(RuleNumber.COLLISION, contactTime, obstacle.id(),
                            safetyDistance, contactDistance,
                            new FailureEvidence(List.of(p0, p1), List.of(q0, q1)));
                }
            }
        }
        return null;
    }

    /** 找到满足 obs[k].t <= t <= obs[k+1].t 的断点下标 k。 */
    private int bracketIndex(List<TimedPoint> obs, double t) {
        int lo = 0;
        int hi = obs.size() - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (obs.get(mid).t() <= t + EPS) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * 在子区间 [from,to] 内求车辆与障碍物距离最早达到/低于安全距离的时刻；
     * 两者位置在区间内均为时间线性函数，相对距离平方是二次函数。
     *
     * @return {@code [时刻, 该时刻距离]}；无接触返回 {@code null}
     */
    private double[] earliestContact(TimedPoint p0, TimedPoint p1, TimedPoint q0, TimedPoint q1,
                                     double from, double to, double safetySquared) {
        double dtVehicle = p1.t() - p0.t();
        double dtObstacle = q1.t() - q0.t();
        double vx = (p1.x() - p0.x()) / dtVehicle - (q1.x() - q0.x()) / dtObstacle;
        double vy = (p1.y() - p0.y()) / dtVehicle - (q1.y() - q0.y()) / dtObstacle;

        double rxAtFrom = interpolate(p0.x(), p1.x(), p0.t(), p1.t(), from)
                - interpolate(q0.x(), q1.x(), q0.t(), q1.t(), from);
        double ryAtFrom = interpolate(p0.y(), p1.y(), p0.t(), p1.t(), from)
                - interpolate(q0.y(), q1.y(), q0.t(), q1.t(), from);
        double span = to - from;

        // f(s) = |R0 + V*s|^2，s 为距 from 的秒数
        double quad = vx * vx + vy * vy;
        double linear = 2.0 * (rxAtFrom * vx + ryAtFrom * vy);
        double fAtStart = rxAtFrom * rxAtFrom + ryAtFrom * ryAtFrom;

        double sMin = 0.0;
        if (quad > EPS) {
            sMin = Math.max(0.0, Math.min(span, -linear / (2.0 * quad)));
        }
        double fMin = quad * sMin * sMin + linear * sMin + fAtStart;
        if (fMin > safetySquared + EPS) {
            return null;
        }

        double contactTime;
        if (fAtStart <= safetySquared + EPS) {
            contactTime = from;
        } else {
            // f(s)=safetySquared 的较小正根
            double constant = fAtStart - safetySquared;
            double discriminant = linear * linear - 4.0 * quad * constant;
            double sRoot;
            if (quad <= EPS || discriminant < 0.0) {
                sRoot = sMin;
            } else {
                sRoot = (-linear - Math.sqrt(discriminant)) / (2.0 * quad);
                if (sRoot < -EPS || sRoot > span + EPS) {
                    sRoot = sMin;
                }
                sRoot = Math.max(0.0, Math.min(span, sRoot));
            }
            contactTime = from + sRoot;
        }
        double rx = rxAtFrom + vx * (contactTime - from);
        double ry = ryAtFrom + vy * (contactTime - from);
        return new double[]{contactTime, Math.sqrt(rx * rx + ry * ry)};
    }

    private double interpolate(double fromValue, double toValue, double fromT, double toT, double t) {
        double ratio = (t - fromT) / (toT - fromT);
        return fromValue + (toValue - fromValue) * ratio;
    }

    private static String ruleEnumName(String ruleId) {
        return switch (ruleId) {
            case "R1" -> "TIME_CONTINUITY";
            case "R2" -> "SPEED";
            case "R3" -> "ACCELERATION";
            case "R4" -> "CURVATURE";
            case "R5" -> "ROAD_BOUNDARY";
            case "R6" -> "COLLISION";
            default -> throw new IllegalArgumentException("未知规则编号: " + ruleId);
        };
    }
}
