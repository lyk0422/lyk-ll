package com.example.starter.trajectory.service;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleStatus;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TrajectoryValidationServiceH2Test {

    @Autowired
    private TrajectoryValidationService validationService;

    @Autowired
    private RuleConfigService ruleConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM validation_record");
        jdbcTemplate.update("DELETE FROM rule_config");
        ruleConfigService.createDraft(rules("v1"));
        ruleConfigService.publish("v1");
    }

    @Test
    void sameKeySameParametersReturnsStoredResultWithStableHash() {
        ValidationRequest safeRequest = safeRequest("idem-safe");
        ValidationResult first = validationService.validate(safeRequest);
        assertTrue(first.safe());

        ValidationResult second = validationService.validate(safeRequest);
        assertEquals(first.resultHash(), second.resultHash());
        assertEquals(first.summary(), second.summary());
        assertEquals(1, countRecords("idem-safe"));
    }

    @Test
    void sameKeyDifferentParametersReturnsConflict() {
        validationService.validate(safeRequest("idem-conflict"));
        ValidationRequest changed = request("idem-conflict", "v1", vehicleAt(50, 50),
                List.of(point(0.5, 52, 99)), List.of(), squareRoad());
        assertThrows(ConflictingRequestException.class, () -> validationService.validate(changed));
    }

    @Test
    void validatingAgainstAnotherVersionCanChangeOutcome() {
        ValidationRequest request = request("version-switch", "v1", vehicleAt(50, 50, 10),
                List.of(point(0.5, 55, 50), point(1.5, 65, 50)), List.of(), squareRoad());
        ValidationResult underV1 = validationService.validate(request);
        assertTrue(underV1.safe());

        ruleConfigService.createDraft(new RuleConfig("v2-strict", 0.5, 10.0, 5.0, 0.1, 0.2, 2.0,
                RuleStatus.DRAFT));
        ruleConfigService.publish("v2-strict");
        ValidationRequest underV2 = request("version-switch-v2", "v2-strict", vehicleAt(50, 50, 10),
                List.of(point(0.5, 55, 50), point(1.5, 65, 50)), List.of(), squareRoad());
        ValidationResult resultV2 = validationService.validate(underV2);
        assertFalse(resultV2.safe());
        assertEquals(1, resultV2.violations().size());
        assertEquals(1.5, resultV2.violations().get(0).firstTime(), 1e-12);
    }

    @Test
    void concurrentFirstSubmissionsWithSameKeyShareOneResult() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ValidationResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                ValidationRequest request = safeRequest("concurrent-key");
                futures.add(pool.submit((Callable<ValidationResult>) () -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return validationService.validate(request);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            String hash = null;
            for (Future<ValidationResult> future : futures) {
                ValidationResult result = future.get(20, TimeUnit.SECONDS);
                if (hash == null) {
                    hash = result.resultHash();
                }
                assertEquals(hash, result.resultHash());
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, countRecords("concurrent-key"));
    }

    @Test
    void multiRuleFailureTrajectoryIsReportedWithOrderedEvidence() {
        ValidationRequest request = request("multi-fail", "v1", vehicleAt(50, 50),
                List.of(
                        point(0.5, 55, 50),
                        point(2.0, 80, 50),
                        point(2.5, 120, 50)),
                List.of(obstacle("OBS-A",
                        List.of(predicted(0, 80, 50), predicted(2.5, 120, 50)))),
                squareRoad());
        ValidationResult result = validationService.validate(request);
        assertFalse(result.safe());
        assertTrue(result.violations().size() >= 3);
        for (int i = 1; i < result.violations().size(); i++) {
            assertTrue(com.example.starter.trajectory.domain.Violation.fixedOrder(
                    result.violations().get(i - 1), result.violations().get(i)) <= 0);
        }
    }

    private ValidationRequest safeRequest(String requestId) {
        return request(requestId, "v1", vehicleAt(50, 50, 4),
                List.of(point(0.5, 52, 50), point(1.0, 54, 50)),
                List.of(obstacle("OBS-1", List.of(predicted(0, 90, 90), predicted(1, 90, 90)))),
                squareRoad());
    }

    private int countRecords(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM validation_record WHERE request_id = ?",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }
}
