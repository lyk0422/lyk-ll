package com.example.starter.audit.engine;

import com.example.starter.audit.Stage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 确定性模拟阶段执行器。
 *
 * <p>指标与产物内容仅由阶段、种子、绑定版本和上游产物摘要链决定，不包含执行次数、
 * 时间等非确定输入；相同绑定与种子的重放（含恢复后重跑）产生完全一致的指标与 SHA-256。
 * 失败通过 failStage/failTimes 注入，用于模拟感知/定位/规划/控制阶段失败。</p>
 */
@Component
public class DeterministicStageExecutor implements StageExecutor {

    /** 每个阶段的指标口径（顺序即输出顺序），用于比较时判定指标集合是否一致。 */
    public static final Map<Stage, List<String>> METRIC_SPEC;

    static {
        Map<Stage, List<String>> spec = new LinkedHashMap<>();
        spec.put(Stage.PERCEPTION, List.of("objects_detected", "detection_score", "latency_ms"));
        spec.put(Stage.LOCALIZATION, List.of("position_error_m", "heading_error_deg", "localization_confidence"));
        spec.put(Stage.PLANNING, List.of("path_length_m", "max_curvature", "planning_score"));
        spec.put(Stage.CONTROL, List.of("tracking_error_m", "lateral_rms_m", "control_effort"));
        METRIC_SPEC = Map.copyOf(spec);
    }

    private final long stepDelayMillis;

    public DeterministicStageExecutor(
            @Value("${audit.step-delay-millis:10}") long stepDelayMillis) {
        this.stepDelayMillis = stepDelayMillis;
    }

    @Override
    public StageExecutionResult execute(StageExecutionContext context) {
        // 注入失败：指定阶段前 failTimes 次执行直接失败（恢复重跑 attempt 递增，之后成功）
        if (context.getStage().name().equals(context.getFailStage())
                && context.getAttempt() <= Math.max(context.getFailTimes(), 1)) {
            throw new StageExecutionException(context.getStage().name(),
                    "模拟阶段执行失败：" + context.getStage().getDisplayName()
                            + " 第 " + context.getAttempt() + " 次执行注入异常");
        }

        // 模拟真实阶段耗时，使中断与并发场景可观测；不影响确定性结果
        if (stepDelayMillis > 0) {
            try {
                Thread.sleep(stepDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StageExecutionException(context.getStage().name(),
                        "阶段执行被中断：" + context.getStage().getDisplayName());
            }
        }

        String upstreamChain = canonicalUpstream(context.getUpstreamDigests());
        String basis = String.join("|",
                context.getStage().name(),
                Long.toString(context.getSeed()),
                context.getCodeVersion(),
                context.getConfigVersion(),
                context.getMapVersion(),
                context.getRosbagVersion(),
                upstreamChain);
        byte[] seedHash = rawSha256(basis);
        Random random = new Random(longFromBytes(seedHash));

        Map<String, Double> metrics = new LinkedHashMap<>();
        for (String metric : METRIC_SPEC.get(context.getStage())) {
            metrics.put(metric, round(metricValue(metric, context.getStage(), random)));
        }

        String artifactPath = "artifacts/task-" + context.getTaskId()
                + "/" + context.getStage().name() + "/output.manifest";
        String replayCommand = buildReplayCommand(context, upstreamChain);
        byte[] artifactContent = buildArtifactContent(context, metrics, upstreamChain, replayCommand);
        String artifactSha256 = DigestUtil.sha256(artifactContent);

        return new StageExecutionResult(metrics, artifactContent, artifactPath, artifactSha256,
                replayCommand);
    }

    /** 按指标名给出符合物理含义区间的模拟值，随机性来自确定性种子。 */
    private double metricValue(String metric, Stage stage, Random random) {
        return switch (metric) {
            case "objects_detected" -> 10 + random.nextInt(90);
            case "detection_score", "localization_confidence", "planning_score" ->
                    0.80 + random.nextDouble() * 0.19;
            case "latency_ms" -> 5.0 + random.nextDouble() * 45.0;
            case "position_error_m", "tracking_error_m" -> random.nextDouble() * 0.25;
            case "heading_error_deg" -> random.nextDouble() * 2.0;
            case "path_length_m" -> 500.0 + random.nextDouble() * 1500.0;
            case "max_curvature" -> random.nextDouble() * 0.12;
            case "lateral_rms_m" -> random.nextDouble() * 0.15;
            case "control_effort" -> 0.2 + random.nextDouble() * 0.6;
            default -> random.nextDouble();
        };
    }

    /** 统一保留 6 位小数，保证 JSON 与结果哈希跨进程格式稳定。 */
    private double round(double value) {
        return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
    }

    /** 上游摘要链规范化：按阶段顺序拼接，作为下游确定性输入的一部分。 */
    public static String canonicalUpstream(Map<String, String> upstreamDigests) {
        if (upstreamDigests == null || upstreamDigests.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>(upstreamDigests);
        List<String> parts = new ArrayList<>();
        sorted.forEach((stage, digest) -> parts.add(stage + "=" + digest));
        return String.join(";", parts);
    }

    /** 规范化指标文本：按键排序，每行 name=value，用于产物与结果哈希。 */
    public static String canonicalMetrics(Map<String, Double> metrics) {
        TreeMap<String, Double> sorted = new TreeMap<>(metrics);
        List<String> parts = new ArrayList<>();
        sorted.forEach((name, value) -> parts.add(name + "=" + value));
        return String.join("\n", parts);
    }

    private String buildReplayCommand(StageExecutionContext context, String upstreamChain) {
        // 重放命令不含 taskId：仅绑定版本、种子与上游摘要即可复现，保证跨任务摘要一致
        StringBuilder sb = new StringBuilder("replay run");
        sb.append(" --stage ").append(context.getStage().name());
        sb.append(" --seed ").append(context.getSeed());
        sb.append(" --code ").append(shellQuote(context.getCodeVersion()));
        sb.append(" --config ").append(shellQuote(context.getConfigVersion()));
        sb.append(" --map ").append(shellQuote(context.getMapVersion()));
        sb.append(" --rosbag ").append(shellQuote(context.getRosbagVersion()));
        if (!upstreamChain.isEmpty()) {
            sb.append(" --upstream ").append(shellQuote(upstreamChain));
        }
        return sb.length() > 2000 ? sb.substring(0, 2000) : sb.toString();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private byte[] buildArtifactContent(StageExecutionContext context, Map<String, Double> metrics,
                                        String upstreamChain, String replayCommand) {
        StringBuilder sb = new StringBuilder();
        sb.append("# simulated autonomous-driving stage artifact\n");
        sb.append("stage=").append(context.getStage().name()).append('\n');
        // 产物内容不含 taskId 与时间，保证相同绑定与种子的重放产物 SHA-256 一致
        sb.append("seed=").append(context.getSeed()).append('\n');
        sb.append("code_version=").append(context.getCodeVersion()).append('\n');
        sb.append("config_version=").append(context.getConfigVersion()).append('\n');
        sb.append("map_version=").append(context.getMapVersion()).append('\n');
        sb.append("rosbag_version=").append(context.getRosbagVersion()).append('\n');
        sb.append("upstream=").append(upstreamChain).append('\n');
        sb.append("[metrics]\n");
        sb.append(canonicalMetrics(metrics)).append('\n');
        sb.append("[replay]\n");
        sb.append(replayCommand).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long longFromBytes(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[i] & 0xFFL);
        }
        return value;
    }

    private static byte[] rawSha256(String content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
