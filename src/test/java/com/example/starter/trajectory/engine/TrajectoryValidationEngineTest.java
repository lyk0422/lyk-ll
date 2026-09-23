package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.ValidationResult;
import com.example.starter.trajectory.domain.Violation;
import com.example.starter.trajectory.engine.checker.AccelerationChecker;
import com.example.starter.trajectory.engine.checker.CollisionChecker;
import com.example.starter.trajectory.engine.checker.CurvatureChecker;
import com.example.starter.trajectory.engine.checker.RoadBoundaryChecker;
import com.example.starter.trajectory.engine.checker.SpeedChecker;
import com.example.starter.trajectory.engine.checker.TimeContinuityChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.starter.trajectory.TestFixtures.obstacle;
import static com.example.starter.trajectory.TestFixtures.point;
import static com.example.starter.trajectory.TestFixtures.predicted;
import static com.example.starter.trajectory.TestFixtures.request;
import static com.example.starter.trajectory.TestFixtures.rules;
import static com.example.starter.trajectory.TestFixtures.squareRoad;
import static com.example.starter.trajectory.TestFixtures.vehicleAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrajectoryValidationEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TrajectoryValidationEngine engine = new TrajectoryValidationEngine(
            objectMapper,
            List.of(
                    new TimeContinuityChecker(objectMapper),
                    new SpeedChecker(objectMapper),
                    new AccelerationChecker(objectMapper),
                    new CurvatureChecker(objectMapper),
                    new RoadBoundaryChecker(objectMapper),
                    new CollisionChecker(objectMapper)));
    private final RuleConfig config = rules("v1");

    @Test
    void safeTrajectoryProducesStableSafeResult() {
        ValidationRequest request = request("safe-1", "v1", vehicleAt(50, 50, 4),
                List.of(point(0.5, 52, 50), point(1.0, 54, 50)),
                List.of(obstacle("OBS-1", List.of(predicted(0, 90, 90), predicted(1, 90, 90)))),
                squareRoad());
        ValidationResult result = engine.validate(request, config);
        assertTrue(result.safe());
        assertTrue(result.violations().isEmpty());
        assertEquals("SAFE: no violation", result.summary());
        assertEquals(64, result.resultHash().length());
    }

    @Test
    void repeatedParallelRunsProduceIdenticalHashAndOrder() throws Exception {
        ValidationRequest request = multiRuleFailureRequest("det-1");
        ValidationResult first = engine.validate(request, config);
        assertFalse(first.safe());
        assertOrderedByRuleThenTimeThenObject(first.violations());

        int threads = 8;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ValidationResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads * perThread; i++) {
                futures.add(pool.submit((Callable<ValidationResult>) () -> engine.validate(request, config)));
            }
            for (Future<ValidationResult> future : futures) {
                ValidationResult result = future.get(10, TimeUnit.SECONDS);
                assertEquals(first.resultHash(), result.resultHash());
                assertEquals(first.summary(), result.summary());
                assertEquals(first.violations().size(), result.violations().size());
                for (int j = 0; j < first.violations().size(); j++) {
                    assertEquals(first.violations().get(j).ruleNumber(),
                            result.violations().get(j).ruleNumber());
                    assertEquals(first.violations().get(j).firstTime(),
                            result.violations().get(j).firstTime(), 0.0);
                    assertEquals(first.violations().get(j).objectId(),
                            result.violations().get(j).objectId());
                    assertEquals(first.violations().get(j).evidence(),
                            result.violations().get(j).evidence());
                }
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private ValidationRequest multiRuleFailureRequest(String requestId) {
        return request(requestId, "v1", vehicleAt(50, 50),
                List.of(
                        point(0.5, 55, 50),
                        point(2.0, 80, 50),
                        point(2.5, 120, 50)),
                List.of(
                        obstacle("OBS-A", List.of(predicted(0, 80, 50), predicted(2.5, 120, 50))),
                        obstacle("OBS-B", List.of(predicted(2.0, 80, 50)))),
                squareRoad());
    }

    private void assertOrderedByRuleThenTimeThenObject(List<Violation> violations) {
        for (int i = 1; i < violations.size(); i++) {
            assertTrue(Violation.fixedOrder(violations.get(i - 1), violations.get(i)) <= 0);
        }
    }
}
