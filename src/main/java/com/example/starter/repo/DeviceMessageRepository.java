package com.example.starter.repo;

import com.example.starter.domain.MessageStatus;
import com.example.starter.po.DeviceMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 设备消息（待发 / in-flight）数据访问。 */
public interface DeviceMessageRepository {

    DeviceMessage insert(String deviceId, String topic, long seq, int priority,
                         String dedupKey, String payload, Instant now);

    Optional<DeviceMessage> findByDedupKey(String dedupKey);

    List<DeviceMessage> findAll();

    /** 按补发顺序取一批待发消息：优先级降序、设备本地序号升序。 */
    List<DeviceMessage> findPending(int limit);

    /** Topic 内当前待发（非 in-flight）消息数。 */
    int countPendingByTopic(String topic);

    /** 全部存活消息数（待发 + in-flight）。 */
    long countAll();

    /** 指定状态的消息数。 */
    long countByStatus(MessageStatus status);

    Optional<DeviceMessage> findLatestPendingByTopic(String topic);

    /**
     * 条件抢占：仅当消息仍为 PENDING 时标记为 in-flight，记录发送时间并累加 attempts。
     * 用条件 UPDATE 避免两个调度线程把同一条消息发送两次。
     *
     * @return true 表示本次抢占成功
     */
    boolean markInFlight(long id, Instant now);

    /** 抢占后 dispatch 失败（链路抖动）时回退为待发，返回是否回退成功。 */
    boolean revertToPending(long id);

    /** 按主键删除，返回被删消息。 */
    Optional<DeviceMessage> deleteById(long id);

    /** 精确按 dedupKey 删除（用于 ACK），返回被删消息。 */
    Optional<DeviceMessage> deleteByDedupKey(String dedupKey);

    /** 超时 in-flight 重置为待发，返回实际重置的消息（用于逐条审计）。 */
    List<DeviceMessage> resetStaleInFlight(Instant cutoff);

    /** 启动恢复：全部 in-flight 重置为待发，返回实际重置的消息（用于逐条审计）。 */
    List<DeviceMessage> resetAllInFlight();

    /** 驱逐候选：全局最低优先级、最旧且非 in-flight 的一条。 */
    Optional<DeviceMessage> findEvictable();

    /** Topic 内最旧的待发消息（BOUNDED 溢出用）。 */
    Optional<DeviceMessage> findOldestPendingByTopic(String topic);
}
