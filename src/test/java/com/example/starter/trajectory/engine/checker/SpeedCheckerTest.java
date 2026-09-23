package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleNumber;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Violation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.starter.trajectory.TestFixtures.point;
import static com.example.starter.trajectory.TestFixtures.request;
import static com.example.starter.trajectory.TestFixtures.rules;
import static com.example.starter.trajectory.TestFixtures.squareRoad;
import static com.example.starter.trajectory.TestFixtures.vehicleAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedCheckerTest {

    private final SpeedChecker checker = new SpeedChecker(new ObjectMapper());
    private final RuleConfig config = rules("v1");

    @Test
    void speedAtLimitPasses() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(1.0, 10, 0), point(2.0, 20, 0)), List.of(), squareRoad());
        assertTrue(checker.check(request, config).isEmpty());
    }

    @Test
    void speedAboveLimitFailsWithFirstSegmentEvidence() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(1.0, 10, 0), point(2.0, 20.5, 0)), List.of(), squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        Violation v = violations.get(0);
        assertEquals(RuleNumber.SPEED, v.ruleNumber());
        assertEquals(2.0, v.firstTime(), 1e-12);
        assertEquals(10.5, v.actual(), 1e-9);
        assertEquals(10.0, v.threshold(), 1e-12);
        assertTrue(v.evidence().contains("segmentSpeed"));
    }
}
