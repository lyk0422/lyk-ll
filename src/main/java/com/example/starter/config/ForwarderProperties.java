package com.example.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 转发器运行参数。
 */
@ConfigurationProperties(prefix = "forwarder")
public class ForwarderProperties {

    /** H2 文件库与运行数据所在目录。 */
    private String dataDir = "./data";
    /** 待发队列总容量（条数），0 或负数表示不限。单位：条。 */
    private int queueCapacity = 200000;
    /** in-flight 超时（毫秒），超时未 ACK 的消息可重新投递。 */
    private long inflightTimeoutMs = 30000;
    /** 调度扫描间隔（毫秒）。 */
    private long dispatchIntervalMs = 500;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getInflightTimeoutMs() {
        return inflightTimeoutMs;
    }

    public void setInflightTimeoutMs(long inflightTimeoutMs) {
        this.inflightTimeoutMs = inflightTimeoutMs;
    }

    public long getDispatchIntervalMs() {
        return dispatchIntervalMs;
    }

    public void setDispatchIntervalMs(long dispatchIntervalMs) {
        this.dispatchIntervalMs = dispatchIntervalMs;
    }
}
