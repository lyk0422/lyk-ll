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
import static com.example.starter.trajectory.TestFixtures.vehicleAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBoundaryCheckerTest {

    private final RoadBoundaryChecker checker = new RoadBoundaryChecker(new ObjectMapper());
    private final RuleConfig config = rules("v1");

    @Test
    void pointOnBoundaryIsLegal() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(1, 50, 0), point(2, 100, 50)), List.of(), squareRoad());
        assertTrue(checker.check(request, config).isEmpty());
    }

    @Test
    void pointOutsideButWithinToleranceIsLegal() {
        ValidationRequest request = request("r", "v1", vehicleAt(50, 50),
                List.of(point(1, 50, 100.15)), List.of(), squareRoad());
        assertTrue(checker.check(request, config).isEmpty());
    }

    @Test
    void pointBeyondToleranceFails() {
        ValidationRequest request = request("r", "v1", vehicleAt(50, 50),
                List.of(point(1, 50, 100.5)), List.of(), squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        Violation v = violations.get(0);
        assertEquals(RuleNumber.ROAD_BOUNDARY, v.ruleNumber());
        assertEquals(1.0, v.firstTime(), 1e-12);
        assertEquals(0.5, v.actual(), 1e-9);
        assertEquals(0.2, v.threshold(), 1e-12);
        assertTrue(v.evidence().contains("distanceToBoundary"));
    }

    @Test
    void initialVehicleOutsideRoadFailsAtTimeZero() {
        ValidationRequest request = request("r", "v1",
                new VehicleState(new Vec(50, 110), 0.0, 0.0),
                List.of(point(1, 50, 50)), List.of(), squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        assertEquals(0.0, violations.get(0).firstTime(), 1e-12);
    }
}
