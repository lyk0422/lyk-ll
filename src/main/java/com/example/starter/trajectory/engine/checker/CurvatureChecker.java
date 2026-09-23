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
 * 规则4：曲率。由三个连续采样点的外接圆半径计算曲率
 * κ = |(B-A)×(C-B)| / (|AB|·|BC|·|CA|)，共线时曲率为 0；
 * κ 绝对值超过 maxCurvature 即失败，首次触发时间取三点中间点的时间。
 */
@Component
public class CurvatureChecker implements RuleChecker {

    private final ObjectMapper objectMapper;

    public CurvatureChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Violation> check(ValidationRequest request, RuleConfig rules) {
        SampledPath path = SampledPath.of(request);
        for (int i = 2; i < path.times.length; i++) {
            if (path.times[i - 1] <= path.times[i - 2] || path.times[i] <= path.times[i - 1]) {
                continue;
            }
            Vec a = path.positions[i - 2];
            Vec b = path.positions[i - 1];
            Vec c = path.positions[i];
            double abx = b.x() - a.x();
            double aby = b.y() - a.y();
            double bcx = c.x() - b.x();
            double bcy = c.y() - b.y();
            double cross = Math.abs(abx * bcy - aby * bcx);
            double ab = a.distanceTo(b);
            double bc = b.distanceTo(c);
            double ca = c.distanceTo(a);
            double curvature = 0.0;
            if (ab > 0 && bc > 0 && ca > 0) {
                curvature = 2.0 * cross / (ab * bc * ca);
            }
            if (curvature > rules.maxCurvature()) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("first", pointView(path.times[i - 2], a));
                evidence.put("middle", pointView(path.times[i - 1], b));
                evidence.put("last", pointView(path.times[i], c));
                evidence.put("curvature", curvature);
                return List.of(new Violation(
                        RuleNumber.CURVATURE,
                        path.times[i - 1],
                        Violation.EGO,
                        rules.maxCurvature(),
                        curvature,
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
