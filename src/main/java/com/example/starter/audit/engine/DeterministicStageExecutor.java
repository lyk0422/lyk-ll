package com.example.starter.audit.engine;

import com.example.starter.audit.domain.StageContext;
import com.example.starter.audit.domain.StageExecutor;
import com.example.starter.audit.domain.StageFailureException;
import com.example.starter.audit.domain.StageInterruptedException;
import com.example.starter.audit.domain.StageProduct;
import com.example.starter.audit.model.Bindings;
import com.example.starter.audit.model.Stage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 确定性阶段执行器：用可复现的伪随机数模拟感知、定位、规划、控制。
 * 输出仅取决于阶段、绑定版本、种子与上游产物摘要，与执行时刻无关；
 * 合成故障（fail:/interrupt: 前缀）仅在首批（attempt=1）注入，
 * 使失败/中断场景可经检查点恢复，重跑结果仍与首次成功重放一致。
 */
@Component
public class DeterministicStageExecutor implements StageExecutor {

    private final DigestSupport digestSupport;

    public DeterministicStageExecutor(DigestSupport digestSupport) {
        this.digestSupport = digestSupport;
    }

    @Override
    public StageProduct execute(StageContext context)
            throws StageFailureException, StageInterruptedException {
        injectFaultIfFirstAttempt(context);

        Bindings b = context.bindings();
        String basis = canonicalBasis(context);
        Random random = new Random(digestSupport.seedFromDigest(digestSupport.sha256Hex(basis)));

        Map<String, Double> metrics = metricsFor(context.stage(), random,
                "extended".equalsIgnoreCase(context.metricsProfile()));
        String replayCommand = buildReplayCommand(context);
        byte[] artifactBytes = buildArtifactBytes(context, metrics, replayCommand);
        return new StageProduct(metrics, artifactBytes, replayCommand);
    }

    private void injectFaultIfFirstAttempt(StageContext context)
            throws StageFailureException, StageInterruptedException {
        String plan = context.faultPlan();
        if (plan == null || context.attempt() != 1) {
            return;
        }
        String[] parts = plan.split(":", 2);
        if (parts.length != 2 || !parts[1].equals(context.stage().getCode())) {
            return;
        }
        if ("fail".equals(parts[0])) {
            throw new StageFailureException("阶段 " + context.stage().getCode()
                    + " 合成执行失败：模拟瞬时处理错误");
        }
        if ("interrupt".equals(parts[0])) {
            throw new StageInterruptedException("阶段 " + context.stage().getCode()
                    + " 合成执行中断：模拟外部中断信号");
        }
    }

    private String canonicalBasis(StageContext context) {
        Bindings b = context.bindings();
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("stage", context.stage().getCode());
        basis.put("code", b.codeVersion());
        basis.put("config", b.configVersion());
        basis.put("map", b.mapVersion());
        basis.put("rosbag", b.rosbagVersion());
        basis.put("seed", b.seed());
        basis.put("upstream", context.upstreamDigest());
        basis.put("profile", "extended".equalsIgnoreCase(context.metricsProfile())
                ? "extended" : "default");
        return digestSupport.canonicalJson(basis);
    }

    private Map<String, Double> metricsFor(Stage stage, Random random, boolean extended) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        switch (stage) {
            case PERCEPTION -> {
                metrics.put("objects_detected", (double) (20 + random.nextInt(40)));
                metrics.put("confidence_mean", round(random.nextDouble(0.7, 0.99)));
                metrics.put("latency_ms", round(random.nextDouble(15, 45)));
                if (extended) {
                    metrics.put("objects_classified", (double) (10 + random.nextInt(30)));
                }
            }
            case LOCALIZATION -> {
                metrics.put("pos_error_m", round(random.nextDouble(0.02, 0.3)));
                metrics.put("heading_error_deg", round(random.nextDouble(0.1, 2.0)));
                metrics.put("latency_ms", round(random.nextDouble(8, 25)));
                if (extended) {
                    metrics.put("yaw_rate_bias_dps", round(random.nextDouble(-0.05, 0.05)));
                }
            }
            case PLANNING -> {
                metrics.put("path_length_m", round(random.nextDouble(80, 500)));
                metrics.put("max_curvature", round(random.nextDouble(0.01, 0.12)));
                metrics.put("planning_time_ms", round(random.nextDouble(20, 90)));
                if (extended) {
                    metrics.put("lane_changes", (double) random.nextInt(5));
                }
            }
            case CONTROL -> {
                metrics.put("lateral_rms_m", round(random.nextDouble(0.01, 0.15)));
                metrics.put("longitudinal_rms_ms2", round(random.nextDouble(0.05, 0.6)));
                metrics.put("control_freq_hz", round(random.nextDouble(48, 52)));
                if (extended) {
                    metrics.put("brake_events", (double) random.nextInt(8));
                }
            }
        }
        return metrics;
    }

    private String buildReplayCommand(StageContext context) {
        Bindings b = context.bindings();
        return "auto-runner replay --stage " + context.stage().getCode()
                + " --code " + b.codeVersion()
                + " --config " + b.configVersion()
                + " --map " + b.mapVersion()
                + " --rosbag " + b.rosbagVersion()
                + " --seed " + b.seed()
                + " --upstream-digest " + context.upstreamDigest();
    }

    private byte[] buildArtifactBytes(StageContext context, Map<String, Double> metrics,
                                      String replayCommand) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Bindings b = context.bindings();
        payload.put("stage", context.stage().getCode());
        payload.put("codeVersion", b.codeVersion());
        payload.put("configVersion", b.configVersion());
        payload.put("mapVersion", b.mapVersion());
        payload.put("rosbagVersion", b.rosbagVersion());
        payload.put("seed", b.seed());
        payload.put("upstreamDigest", context.upstreamDigest());
        payload.put("metrics", metrics);
        payload.put("replayCommand", replayCommand);
        return digestSupport.canonicalJson(payload).getBytes(StandardCharsets.UTF_8);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
