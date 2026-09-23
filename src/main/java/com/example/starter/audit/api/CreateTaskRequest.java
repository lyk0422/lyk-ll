package com.example.starter.audit.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建审计任务请求。
 *
 * <p>codeVersion/configVersion/mapVersion/rosbagVersion 为必填绑定版本（或内容摘要），
 * 任一缺失返回 422；seed 为随机种子；requestId 非空时启用同键幂等。
 * failStage/failTimes/interruptAfter 仅用于模拟失败与中断注入。</p>
 */
public class CreateTaskRequest {

    @NotBlank(message = "taskCode 不能为空")
    private String taskCode;

    private String requestId;

    /** 代码版本或内容摘要，必填。 */
    @NotBlank(message = "codeVersion 不能为空")
    private String codeVersion;

    /** 配置版本或内容摘要，必填。 */
    @NotBlank(message = "configVersion 不能为空")
    private String configVersion;

    /** 地图版本或内容摘要，必填。 */
    @NotBlank(message = "mapVersion 不能为空")
    private String mapVersion;

    /** 输入 rosbag 版本或内容摘要，必填。 */
    @NotBlank(message = "rosbagVersion 不能为空")
    private String rosbagVersion;

    /** 随机种子，确定性重放依据，必填。 */
    @NotNull(message = "seed 不能为空")
    private Long seed;

    /** 模拟注入：在该阶段失败（阶段名，如 PLANNING），为空不注入。 */
    private String failStage;

    /** 模拟注入：failStage 前 failTimes 次执行失败，默认 1。 */
    private Integer failTimes;

    /** 模拟注入：该阶段成功并写检查点后中断任务，为空不注入。 */
    private String interruptAfter;

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public String getFailStage() {
        return failStage;
    }

    public void setFailStage(String failStage) {
        this.failStage = failStage;
    }

    public Integer getFailTimes() {
        return failTimes;
    }

    public void setFailTimes(Integer failTimes) {
        this.failTimes = failTimes;
    }

    public String getInterruptAfter() {
        return interruptAfter;
    }

    public void setInterruptAfter(String interruptAfter) {
        this.interruptAfter = interruptAfter;
    }
}
