package com.example.starter.trajectory.engine.checker;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则1：时间连续性。时间必须严格递增，相邻间隔超过 maxTimeGapSeconds 即失败。
 * 只保留 EGO 维度的首次触发证据。
 */
@Component
public class TimeContinuityChecker implements RuleChecker {

    private final ObjectMapper objectMapper;

    public TimeContinuityChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Violation> check(ValidationRequest request, RuleConfig rules) {
        double prevT = 0.0;
        Vec prevPos = request.vehicle().position();
        for (TrajectoryPoint point : request.trajectory()) {
            double gap = point.t() - prevT;
            boolean notIncreasing = !(gap > 0);
            boolean gapTooLarge = gap > rules.maxTimeGapSeconds();
            if (notIncreasing || gapTooLarge) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("previous", pointView(prevT, prevPos));
                evidence.put("current", pointView(point.t(), point.position()));
                evidence.put("gapSeconds", gap);
                evidence.put("reason", notIncreasing ? "TIME_NOT_STRICTLY_INCREASING" : "TIME_GAP_EXCEEDED");
                return List.of(new Violation(
                        RuleNumber.TIME_CONTINUITY,
                        point.t(),
                        Violation.EGO,
                        rules.maxTimeGapSeconds(),
                        gap,
                        EvidenceJson.write(objectMapper, evidence)));
            }
            prevT = point.t();
            prevPos = point.position();
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
