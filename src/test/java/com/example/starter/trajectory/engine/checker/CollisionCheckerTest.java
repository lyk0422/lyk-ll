package com.example.starter.trajectory.engine.checker;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleNumber;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.Violation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.starter.trajectory.TestFixtures.obstacle;
import static com.example.starter.trajectory.TestFixtures.point;
import static com.example.starter.trajectory.TestFixtures.predicted;
import static com.example.starter.trajectory.TestFixtures.request;
import static com.example.starter.trajectory.TestFixtures.rules;
import static com.example.starter.trajectory.TestFixtures.squareRoad;
import static com.example.starter.trajectory.TestFixtures.vehicleAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollisionCheckerTest {

    private final CollisionChecker checker = new CollisionChecker(new ObjectMapper());
    private final RuleConfig config = rules("v1");

    @Test
    void distanceExactlySafeIsCollisionRisk() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(1, 10, 0)),
                List.of(obstacle("OBS-1", List.of(predicted(0, 5, 0), predicted(1, 12, 0)))),
                squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        Violation v = violations.get(0);
        assertEquals(RuleNumber.COLLISION, v.ruleNumber());
        assertEquals("OBS-1", v.objectId());
        assertEquals(1.0, v.firstTime(), 1e-12);
        assertEquals(2.0, v.actual(), 1e-9);
        assertEquals(2.0, v.threshold(), 1e-12);
    }

    @Test
    void distanceJustAboveSafePasses() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(1, 10, 0)),
                List.of(obstacle("OBS-1", List.of(predicted(1, 12.01, 0)))),
                squareRoad());
        assertTrue(checker.check(request, config).isEmpty());
    }

    @Test
    void firstTriggerIsKeptForEachObstacle() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(0.5, 1, 0), point(1, 2, 0), point(1.5, 3, 0)),
                List.of(obstacle("OBS-1", List.of(predicted(0, 10, 0),
                        predicted(0.5, 2.5, 0), predicted(1.5, 4, 0)))),
                squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(1, violations.size());
        assertEquals(0.5, violations.get(0).firstTime(), 1e-12);
        assertEquals(1.5, violations.get(0).actual(), 1e-9);
    }

    @Test
    void eachObstacleProducesItsOwnEvidenceOrderedByIdLaterInEngine() {
        ValidationRequest request = request("r", "v1", vehicleAt(0, 0),
                List.of(point(1, 10, 0)),
                List.of(
                        obstacle("OBS-A", List.of(predicted(1, 10, 0))),
                        obstacle("OBS-B", List.of(predicted(1, 11, 0)))),
                squareRoad());
        List<Violation> violations = checker.check(request, config);
        assertEquals(2, violations.size());
    }
}
