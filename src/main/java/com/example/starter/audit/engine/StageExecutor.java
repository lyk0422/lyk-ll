package com.example.starter.audit.engine;

import com.example.starter.audit.Stage;

import java.util.Map;

/**
 * 可注入的阶段执行器：模拟感知、定位、规划、控制阶段，不依赖真实自动驾驶栈。
 *
 * <p>实现必须满足：相同绑定版本、种子与上游摘要时，阶段指标与产物摘要确定且可复现。</p>
 */
public interface StageExecutor {

    /**
     * 执行单个阶段。
     *
     * @param context 阶段执行上下文（绑定版本、种子、上游产物摘要链、失败注入配置）
     * @return 阶段执行结果（指标、产物内容与相对路径、可重放命令）
     * @throws StageExecutionException 阶段执行失败时抛出，由编排层记录失败状态
     */
    StageExecutionResult execute(StageExecutionContext context);

    /** 阶段执行入参。 */
    class StageExecutionContext {
        private final Long taskId;
        private final Stage stage;
        private final long seed;
        /** 本阶段第几次执行（从 1 开始），仅用于产物路径与失败注入，不参与确定性摘要。 */
        private final int attempt;
        private final String codeVersion;
        private final String configVersion;
        private final String mapVersion;
        private final String rosbagVersion;
        /** 上游阶段产物摘要：阶段名 -> SHA-256，按依赖顺序排列。 */
        private final Map<String, String> upstreamDigests;
        /** 失败注入阶段名，空表示不注入。 */
        private final String failStage;
        /** 失败注入：该阶段前 failTimes 次执行抛错。 */
        private final int failTimes;

        public StageExecutionContext(Long taskId, Stage stage, long seed, int attempt,
                                     String codeVersion, String configVersion, String mapVersion,
                                     String rosbagVersion, Map<String, String> upstreamDigests,
                                     String failStage, int failTimes) {
            this.taskId = taskId;
            this.stage = stage;
            this.seed = seed;
            this.attempt = attempt;
            this.codeVersion = codeVersion;
            this.configVersion = configVersion;
            this.mapVersion = mapVersion;
            this.rosbagVersion = rosbagVersion;
            this.upstreamDigests = upstreamDigests;
            this.failStage = failStage;
            this.failTimes = failTimes;
        }

        public Long getTaskId() {
            return taskId;
        }

        public Stage getStage() {
            return stage;
        }

        public long getSeed() {
            return seed;
        }

        public int getAttempt() {
            return attempt;
        }

        public String getCodeVersion() {
            return codeVersion;
        }

        public String getConfigVersion() {
            return configVersion;
        }

        public String getMapVersion() {
            return mapVersion;
        }

        public String getRosbagVersion() {
            return rosbagVersion;
        }

        public Map<String, String> getUpstreamDigests() {
            return upstreamDigests;
        }

        public String getFailStage() {
            return failStage;
        }

        public int getFailTimes() {
            return failTimes;
        }
    }
}
