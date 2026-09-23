package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.ObstaclePrediction;
import com.example.starter.trajectory.domain.PredictedPoint;
import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleNumber;
import com.example.starter.trajectory.domain.TrajectoryPoint;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Vec;
import com.example.starter.trajectory.domain.Violation;
import com.example.starter.trajectory.engine.EvidenceJson;
import com.example.starter.trajectory.engine.RuleChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则6：碰撞。在车辆每个采样时刻（初始状态与轨迹点），
 * 按障碍物预测点线性插值得到该时刻障碍物位置；车与障碍物距离
 * 小于或等于 collisionDistance 即视为碰撞风险。
 * 每个障碍物只保留首次触发证据；采样时间超出障碍物预测覆盖范围时跳过。
 */
@Component
public class CollisionChecker implements RuleChecker {

    private final ObjectMapper objectMapper;

    public CollisionChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Violation> check(ValidationRequest request, RuleConfig rules) {
        List<Violation> violations = new ArrayList<>();
        for (ObstaclePrediction obstacle : request.obstacles()) {
            Violation first = checkObstacle(request, obstacle, rules);
            if (first != null) {
                violations.add(first);
            }
        }
        return violations;
    }

    private Violation checkObstacle(ValidationRequest request, ObstaclePrediction obstacle, RuleConfig rules) {
        Violation atZero = checkAtTime(0.0, request.vehicle().position(), obstacle, rules);
        if (atZero != null) {
            return atZero;
        }
        for (TrajectoryPoint point : request.trajectory()) {
            Violation violation = checkAtTime(point.t(), point.position(), obstacle, rules);
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    private Violation checkAtTime(double t, Vec egoPosition, ObstaclePrediction obstacle, RuleConfig rules) {
        Vec predicted = interpolate(obstacle.points(), t);
        if (predicted == null) {
            return null;
        }
        double distance = egoPosition.distanceTo(predicted);
        if (distance > rules.collisionDistance()) {
            return null;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("time", t);
        evidence.put("ego", pointView(t, egoPosition));
        evidence.put("obstacle", pointView(t, predicted));
        evidence.put("distance", distance);
        evidence.put("collisionDistance", rules.collisionDistance());
        return new Violation(
                RuleNumber.COLLISION,
                t,
                obstacle.objectId(),
                rules.collisionDistance(),
                distance,
                EvidenceJson.write(objectMapper, evidence));
    }

    /**
     * 在预测点序列上按时间线性插值；时间超出覆盖范围或序列为空时返回 null。
     */
    private Vec interpolate(List<PredictedPoint> points, double t) {
        if (points.isEmpty()) {
            return null;
        }
        if (t <= points.get(0).t()) {
            return t == points.get(0).t() ? points.get(0).position() : null;
        }
        for (int i = 1; i < points.size(); i++) {
            PredictedPoint prev = points.get(i - 1);
            PredictedPoint next = points.get(i);
            if (t <= next.t()) {
                double span = next.t() - prev.t();
                if (!(span > 0)) {
                    return next.position();
                }
                double ratio = (t - prev.t()) / span;
                return new Vec(
                        prev.position().x() + ratio * (next.position().x() - prev.position().x()),
                        prev.position().y() + ratio * (next.position().y() - prev.position().y()));
            }
        }
        return null;
    }

    private Map<String, Object> pointView(double t, Vec position) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("t", t);
        view.put("x", position.x());
        view.put("y", position.y());
        return view;
    }
}
