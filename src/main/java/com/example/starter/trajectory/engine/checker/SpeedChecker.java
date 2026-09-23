package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleNumber;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Vec;
import com.example.starter.trajectory.domain.Violation;
import com.example.starter.trajectory.engine.EvidenceJson;
import com.example.starter.trajectory.engine.RuleChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则2：速度。相邻采样点间的平均速度超过 maxSpeed 即失败；
 * 时间不严格递增的区间跳过（由时间连续性规则负责报告）。
 */
@Component
public class SpeedChecker implements RuleChecker {

    private final ObjectMapper objectMapper;

    public SpeedChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Violation> check(ValidationRequest request, RuleConfig rules) {
        SampledPath path = SampledPath.of(request);
        for (int i = 1; i < path.times.length; i++) {
            double dt = path.times[i] - path.times[i - 1];
            if (!(dt > 0)) {
                continue;
            }
            Vec from = path.positions[i - 1];
            Vec to = path.positions[i];
            double speed = from.distanceTo(to) / dt;
            if (speed > rules.maxSpeed()) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("previous", pointView(path.times[i - 1], from));
                evidence.put("current", pointView(path.times[i], to));
                evidence.put("segmentSpeed", speed);
                return List.of(new Violation(
                        RuleNumber.SPEED,
                        path.times[i],
                        Violation.EGO,
                        rules.maxSpeed(),
                        speed,
                        EvidenceJson.write(objectMapper, evidence)));
            }
        }
        return List.of();
    }

    private Map<String, Object> pointView(double t, Vec position) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("t", t);
        view.put("x", position.x());
        view.put("y", position.y());
        return view;
    }
}
