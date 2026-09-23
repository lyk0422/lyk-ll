package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.ValidationRequest;
import com.example.starter.trajectory.domain.ValidationResult;
import com.example.starter.trajectory.domain.Violation;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行执行时间连续性、动力学、道路、碰撞四类规则，
 * 输出按规则编号、首次触发时间、对象 ID 固定排序，
 * 并生成确定性摘要与稳定结果哈希。
 */
@Component
public class TrajectoryValidationEngine {

    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final List<RuleChecker> checkers;

    public TrajectoryValidationEngine(ObjectMapper objectMapper, List<RuleChecker> checkers) {
        this.objectMapper = objectMapper;
        this.checkers = checkers;
        AtomicInteger index = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "trajectory-checker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executorService = Executors.newFixedThreadPool(Math.max(2, checkers.size()), threadFactory);
    }

    /**
     * 执行校验。四类规则在独立线程并行运行，结果合并且确定性排序。
     */
    public ValidationResult validate(ValidationRequest request, RuleConfig rules) {
        List<Future<List<Violation>>> futures = new ArrayList<>(checkers.size());
        for (RuleChecker checker : checkers) {
            futures.add(executorService.submit(() -> checker.check(request, rules)));
        }
        Map<String, Violation> firstByKey = new LinkedHashMap<>();
        for (Future<List<Violation>> future : futures) {
            for (Violation violation : get(future)) {
                firstByKey.putIfAbsent(Violation.keyOf(violation.ruleNumber(), violation.objectId()), violation);
            }
        }
        List<Violation> ordered = new ArrayList<>(firstByKey.values());
        ordered.sort(Violation::fixedOrder);

        String canonicalRequest = EvidenceJson.write(objectMapper, request);
        String violationsText = Violation.stableLines(ordered);
        String hashInput = request.requestId() + '\n'
                + rules.ruleVersion() + '\n'
                + canonicalRequest + '\n'
                + violationsText;
        String resultHash = Hashes.sha256Hex(hashInput);
        String summary = buildSummary(ordered);
        return new ValidationResult(
                request.requestId(),
                rules.ruleVersion(),
                ordered.isEmpty(),
                ordered,
                summary,
                resultHash);
    }

    @PreDestroy
    void shutdown() {
        executorService.shutdown();
    }

    private List<Violation> get(Future<List<Violation>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("trajectory validation interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("trajectory validation failed", cause);
        }
    }

    private String buildSummary(List<Violation> ordered) {
        if (ordered.isEmpty()) {
            return "SAFE: no violation";
        }
        List<String> parts = new ArrayList<>(ordered.size());
        for (Violation v : ordered) {
            parts.add("R" + v.ruleNumber().code()
                    + " t=" + Double.toString(v.firstTime())
                    + " object=" + v.objectId()
                    + " actual=" + Double.toString(v.actual())
                    + " threshold=" + Double.toString(v.threshold()));
        }
        return "UNSAFE (" + ordered.size() + "): " + String.join("; ", parts);
    }
}
