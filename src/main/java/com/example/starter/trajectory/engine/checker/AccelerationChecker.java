package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleNumber;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Violation;
import com.example.starter.trajectory.engine.EvidenceJson;
import com.example.starter.trajectory.engine.RuleChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则3：加速度。每个区间的平均速度取在区间中点，车辆初始速度取在 t=0；
 * 相邻速度之差除以中点时间差得到加速度，绝对值超过 maxAcceleration 即失败。
 */
@Component
public class AccelerationChecker implements RuleChecker {

    private final ObjectMapper objectMapper;

    public AccelerationChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Violation> check(ValidationRequest request, RuleConfig rules) {
        SampledPath path = SampledPath.of(request);
        int segments = path.times.length - 1;
        double[] segSpeed = new double[segments + 1];
        double[] segMid = new double[segments + 1];
        segSpeed[0] = request.vehicle().speed();
        segMid[0] = 0.0;
        for (int i = 1; i <= segments; i++) {
            double dt = path.times[i] - path.times[i - 1];
            if (!(dt > 0)) {
                return List.of();
            }
            segSpeed[i] = path.segmentSpeed(i);
            segMid[i] = (path.times[i - 1] + path.times[i]) / 2.0;
        }
        for (int i = 1; i <= segments; i++) {
            double dt = segMid[i] - segMid[i - 1];
            if (!(dt > 0)) {
                continue;
            }
            double acceleration = (segSpeed[i] - segSpeed[i - 1]) / dt;
            if (Math.abs(acceleration) > rules.maxAcceleration()) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("fromTime", segMid[i - 1]);
                evidence.put("toTime", segMid[i]);
                evidence.put("fromSpeed", segSpeed[i - 1]);
                evidence.put("toSpeed", segSpeed[i]);
                evidence.put("acceleration", acceleration);
                return List.of(new Violation(
                        RuleNumber.ACCELERATION,
                        path.times[i],
                        Violation.EGO,
                        rules.maxAcceleration(),
                        Math.abs(acceleration),
                        EvidenceJson.write(objectMapper, evidence)));
            }
        }
        return List.of();
    }
}
