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

class TimeContinuityCheckerTest {

    private final TimeContinuityChecker checker = new TimeContinuityChecker(new ObjectMapper());
    private final RuleConfig config = rules("v1");

    @Test
    void strictlyIncreasingWithinThresholdPasses() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(0.5, 1, 0), point(1.0, 2, 0)), List.of(), squareRoad());
        assertTrue(checker.check(request, config).isEmpty());
    }

    @Test
    void gapExactlyAtThresholdPasses() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(1.0, 1, 0), point(2.0, 2, 0)), List.of(), squareRoad());
        assertTrue(checker.check(request, config).isEmpty());
    }

    @Test
    void gapLargerThanThresholdFailsAtFirstTime() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(0.5, 1, 0), point(2.0, 2, 0), point(2.5, 3, 0)),
                List.of(), squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        Violation v = violations.get(0);
        assertEquals(RuleNumber.TIME_CONTINUITY, v.ruleNumber());
        assertEquals(2.0, v.firstTime(), 1e-12);
        assertEquals(1.5, v.actual(), 1e-12);
        assertEquals(1.0, v.threshold(), 1e-12);
        assertTrue(v.evidence().contains("TIME_GAP_EXCEEDED"));
    }

    @Test
    void nonIncreasingTimeFails() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(0.5, 1, 0), point(0.5, 2, 0)), List.of(), squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        assertEquals(0.5, violations.get(0).firstTime(), 1e-12);
        assertTrue(violations.get(0).evidence().contains("TIME_NOT_STRICTLY_INCREASING"));
    }
}
