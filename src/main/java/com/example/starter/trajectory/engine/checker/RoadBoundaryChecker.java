package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleNumber;
import com.example.starter.trajectory.domain.TrajectoryPoint;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Vec;
import com.example.starter.trajectory.domain.Violation;
import com.example.starter.trajectory.engine.EvidenceJson;
import com.example.starter.trajectory.engine.Geometry;
import com.example.starter.trajectory.engine.RuleChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则5：道路边界。轨迹点位于边界上视为合法；在多边形外且到边界距离
 * 超过 roadTolerance 即失败。车辆初始状态与所有轨迹点均参与检查。
 */
@Component
public class RoadBoundaryChecker implements RuleChecker {

    private final ObjectMapper objectMapper;

    public RoadBoundaryChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Violation> check(ValidationRequest request, RuleConfig rules) {
        List<Vec> polygon = request.road().vertices();
        Violation initial = checkPoint(0.0, request.vehicle().position(), polygon, rules);
        if (initial != null) {
            return List.of(initial);
        }
        for (TrajectoryPoint point : request.trajectory()) {
            Violation violation = checkPoint(point.t(), point.position(), polygon, rules);
            if (violation != null) {
                return List.of(violation);
            }
        }
        return List.of();
    }

    private Violation checkPoint(double t, Vec point, List<Vec> polygon, RuleConfig rules) {
        if (Geometry.isInsideOrOnBoundary(point, polygon)) {
            return null;
        }
        double distance = Geometry.distanceToPolygon(point, polygon);
        if (distance <= rules.roadTolerance()) {
            return null;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("point", pointView(t, point));
        evidence.put("distanceToBoundary", distance);
        evidence.put("roadTolerance", rules.roadTolerance());
        return new Violation(
                RuleNumber.ROAD_BOUNDARY,
                t,
                Violation.EGO,
                rules.roadTolerance(),
                distance,
                EvidenceJson.write(objectMapper, evidence));
    }

    private Map<String, Object> pointView(double t, Vec position) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("t", t);
        view.put("x", position.x());
        view.put("y", position.y());
        return view;
    }
}
