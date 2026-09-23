package com.example.starter.slam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * SLAM 后端配置。回环阈值支持按来源数据版本覆盖（versionOverrides 键为版本号）。
 */
@ConfigurationProperties(prefix = "slam")
public class SlamProperties {

    private Loop loop = new Loop();
    private Opt opt = new Opt();

    public Loop getLoop() {
        return loop;
    }

    public void setLoop(Loop loop) {
        this.loop = loop;
    }

    public Opt getOpt() {
        return opt;
    }

    public void setOpt(Opt opt) {
        this.opt = opt;
    }

    /**
     * 回环约束可信度阈值；分数低于 scoreMin、残差高于 residualMax 或协方差迹超过
     * covarianceTraceMax 的约束保存为 REJECTED 审计记录。
     */
    public static class Loop {
        /**
         * 最低回环匹配分数（无量纲，越高越可信）。
         */
        private double scoreMin = 0.75;
        /**
         * 最大允许初始残差（米/弧度综合量纲，越小越可信）。
         */
        private double residualMax = 0.5;
        /**
         * 6x6 协方差矩阵最大允许迹（单位：米²/弧度²，越小越可信）。
         */
        private double covarianceTraceMax = 1.0;
        /**
         * 版本化阈值覆盖；键为来源数据版本号，未命中版本时使用默认阈值。
         */
        private Map<Integer, Thresholds> versionOverrides = Map.of();

        public double getScoreMin() {
            return scoreMin;
        }

        public void setScoreMin(double scoreMin) {
            this.scoreMin = scoreMin;
        }

        public double getResidualMax() {
            return residualMax;
        }

        public void setResidualMax(double residualMax) {
            this.residualMax = residualMax;
        }

        public double getCovarianceTraceMax() {
            return covarianceTraceMax;
        }

        public void setCovarianceTraceMax(double covarianceTraceMax) {
            this.covarianceTraceMax = covarianceTraceMax;
        }

        public Map<Integer, Thresholds> getVersionOverrides() {
            return versionOverrides;
        }

        public void setVersionOverrides(Map<Integer, Thresholds> versionOverrides) {
            this.versionOverrides = versionOverrides;
        }
    }

    /**
     * 单个版本（或默认）的回环阈值三元组。
     */
    public static class Thresholds {
        private Double scoreMin;
        private Double residualMax;
        private Double covarianceTraceMax;

        public Double getScoreMin() {
            return scoreMin;
        }

        public void setScoreMin(Double scoreMin) {
            this.scoreMin = scoreMin;
        }

        public Double getResidualMax() {
            return residualMax;
        }

        public void setResidualMax(Double residualMax) {
            this.residualMax = residualMax;
        }

        public Double getCovarianceTraceMax() {
            return covarianceTraceMax;
        }

        public void setCovarianceTraceMax(Double covarianceTraceMax) {
            this.covarianceTraceMax = covarianceTraceMax;
        }
    }

    public static class Opt {
        /**
         * 优化工作线程数。
         */
        private int concurrency = 2;
        /**
         * 提交事务提交后是否自动派发待执行任务；测试中可关闭以便精确控制领取时机。
         */
        private boolean autoDispatch = true;

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public boolean isAutoDispatch() {
            return autoDispatch;
        }

        public void setAutoDispatch(boolean autoDispatch) {
            this.autoDispatch = autoDispatch;
        }
    }
}
