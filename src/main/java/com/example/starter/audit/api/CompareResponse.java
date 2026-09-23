package com.example.starter.audit.api;

import java.util.List;
import java.util.Map;

/**
 * 两次任务比较结果。
 *
 * <p>bindingDiffs 为代码/配置/地图/输入/种子五项绑定差异；metricDiffs 为同名指标数值差
 * （右侧任务减左侧任务）；incomparableStages 为阶段集合不一致项；incomparableMetrics
 * 为同名阶段下指标口径（指标集合）不一致项，此类指标不用零值代替。</p>
 */
public class CompareResponse {

    private Long leftTaskId;
    private Long rightTaskId;
    private boolean comparable;
    private Map<String, BindingDiff> bindingDiffs;
    private List<String> incomparableStages;
    private Map<String, Map<String, Double>> metricDiffs;
    private List<MetricIncomparable> incomparableMetrics;

    public Long getLeftTaskId() {
        return leftTaskId;
    }

    public void setLeftTaskId(Long leftTaskId) {
        this.leftTaskId = leftTaskId;
    }

    public Long getRightTaskId() {
        return rightTaskId;
    }

    public void setRightTaskId(Long rightTaskId) {
        this.rightTaskId = rightTaskId;
    }

    public boolean isComparable() {
        return comparable;
    }

    public void setComparable(boolean comparable) {
        this.comparable = comparable;
    }

    public Map<String, BindingDiff> getBindingDiffs() {
        return bindingDiffs;
    }

    public void setBindingDiffs(Map<String, BindingDiff> bindingDiffs) {
        this.bindingDiffs = bindingDiffs;
    }

    public List<String> getIncomparableStages() {
        return incomparableStages;
    }

    public void setIncomparableStages(List<String> incomparableStages) {
        this.incomparableStages = incomparableStages;
    }

    public Map<String, Map<String, Double>> getMetricDiffs() {
        return metricDiffs;
    }

    public void setMetricDiffs(Map<String, Map<String, Double>> metricDiffs) {
        this.metricDiffs = metricDiffs;
    }

    public List<MetricIncomparable> getIncomparableMetrics() {
        return incomparableMetrics;
    }

    public void setIncomparableMetrics(List<MetricIncomparable> incomparableMetrics) {
        this.incomparableMetrics = incomparableMetrics;
    }

    /** 单个绑定项的左右取值与是否一致。 */
    public static class BindingDiff {
        private String left;
        private String right;
        private boolean changed;

        public BindingDiff() {
        }

        public BindingDiff(String left, String right, boolean changed) {
            this.left = left;
            this.right = right;
            this.changed = changed;
        }

        public String getLeft() {
            return left;
        }

        public void setLeft(String left) {
            this.left = left;
        }

        public String getRight() {
            return right;
        }

        public void setRight(String right) {
            this.right = right;
        }

        public boolean isChanged() {
            return changed;
        }

        public void setChanged(boolean changed) {
            this.changed = changed;
        }
    }

    /** 指标口径不一致明细：某阶段仅一侧存在或指标集合不同的指标名。 */
    public static class MetricIncomparable {
        private String stage;
        private String metric;
        private String side;

        public MetricIncomparable() {
        }

        public MetricIncomparable(String stage, String metric, String side) {
            this.stage = stage;
            this.metric = metric;
            this.side = side;
        }

        public String getStage() {
            return stage;
        }

        public void setStage(String stage) {
            this.stage = stage;
        }

        public String getMetric() {
            return metric;
        }

        public void setMetric(String metric) {
            this.metric = metric;
        }

        /** LEFT_ONLY / RIGHT_ONLY，标明该指标只出现在哪一侧。 */
        public String getSide() {
            return side;
        }

        public void setSide(String side) {
            this.side = side;
        }
    }
}
