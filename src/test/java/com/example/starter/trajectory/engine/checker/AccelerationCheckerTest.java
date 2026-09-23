package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleNumber;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Vec;
import com.example.starter.trajectory.domain.VehicleState;
import com.example.starter.trajectory.domain.Violation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.starter.trajectory.TestFixtures.point;
import static com.example.starter.trajectory.TestFixtures.request;
import static com.example.starter.trajectory.TestFixtures.rules;
import static com.example.starter.trajectory.TestFixtures.squareRoad;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccelerationCheckerTest {

    private final AccelerationChecker checker = new AccelerationChecker(new ObjectMapper());
    private final RuleConfig config = rules("v1");

    @Test
    void accelerationAtLimitPasses() {
        VehicleState vehicle = new VehicleState(new Vec(0, 0), 0.0, 0.0);
        ValidationRequest request = request("r", "v1", vehicle,
                List.of(point(1.0, 2.5, 0), point(2.0, 10, 0)), List.of(), squareRoad());
        assertTrue(checker.check(request, config).isEmpty());
    }

    @Test
    void accelerationAboveLimitFailsWithFirstEvidence() {
        VehicleState vehicle = new VehicleState(new Vec(0, 0), 0.0, 0.0);
        ValidationRequest request = request("r", "v1", vehicle,
                List.of(point(1.0, 5, 0), point(2.0, 20, 0)), List.of(), squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        Violation v = violations.get(0);
        assertEquals(RuleNumber.ACCELERATION, v.ruleNumber());
        assertEquals(1.0, v.firstTime(), 1e-12);
        assertEquals(10.0, v.actual(), 1e-9);
        assertEquals(5.0, v.threshold(), 1e-12);
        assertTrue(v.evidence().contains("acceleration"));
    }
}
