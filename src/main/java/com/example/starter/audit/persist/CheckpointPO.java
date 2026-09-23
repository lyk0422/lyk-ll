package com.example.starter.audit.persist;

/**
 * stage_checkpoint 行对象：成功检查点保存的绑定版本、上游摘要链与阶段输出。
 */
public class CheckpointPO {

    private Long id;
    private Long taskId;
    private String stage;
    private Integer checkpointSeq;
    private String codeVersion;
    private String configVersion;
    private String mapVersion;
    private String rosbagVersion;
    private Long seed;
    private String upstreamDigestsJson;
    private String stageOutputDigest;
    private String artifactPath;
    private String artifactSha256;
    private String metricsJson;
    private String replayCommand;
    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public Integer getCheckpointSeq() {
        return checkpointSeq;
    }

    public void setCheckpointSeq(Integer checkpointSeq) {
        this.checkpointSeq = checkpointSeq;
    }

    public String getCodeVersion() {
        return codeVersion;
    }

    public void setCodeVersion(String codeVersion) {
        this.codeVersion = codeVersion;
    }

    public String getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(String configVersion) {
        this.configVersion = configVersion;
    }

    public String getMapVersion() {
        return mapVersion;
    }

    public void setMapVersion(String mapVersion) {
        this.mapVersion = mapVersion;
    }

    public String getRosbagVersion() {
        return rosbagVersion;
    }

    public void setRosbagVersion(String rosbagVersion) {
        this.rosbagVersion = rosbagVersion;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public String getUpstreamDigestsJson() {
        return upstreamDigestsJson;
    }

    public void setUpstreamDigestsJson(String upstreamDigestsJson) {
        this.upstreamDigestsJson = upstreamDigestsJson;
    }

    public String getStageOutputDigest() {
        return stageOutputDigest;
    }

    public void setStageOutputDigest(String stageOutputDigest) {
        this.stageOutputDigest = stageOutputDigest;
    }

    public String getArtifactPath() {
        return artifactPath;
    }

    public void setArtifactPath(String artifactPath) {
        this.artifactPath = artifactPath;
    }

    public String getArtifactSha256() {
        return artifactSha256;
    }

    public void setArtifactSha256(String artifactSha256) {
        this.artifactSha256 = artifactSha256;
    }

    public String getMetricsJson() {
        return metricsJson;
    }

    public void setMetricsJson(String metricsJson) {
        this.metricsJson = metricsJson;
    }

    public String getReplayCommand() {
        return replayCommand;
    }

    public void setReplayCommand(String replayCommand) {
        this.replayCommand = replayCommand;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
