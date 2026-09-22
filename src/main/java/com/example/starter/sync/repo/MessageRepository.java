package com.example.starter.sync.repo;

import java.time.Clock;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.sync.domain.DiscardReason;
import com.example.starter.sync.domain.SensorSample;

/**
 * 传感器消息仓储。(sensor_type, message_id) 唯一约束保证并发/重放下同一消息只落库一次。
 */
@Repository
public class MessageRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MessageRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 落库一条已接受消息。唯一约束冲突时返回 false（并发重复，幂等）。
     */
    public boolean insertAccepted(SensorSample sample) {
        return insert(sample, "ACCEPTED", null);
    }

    /**
     * 落库一条被丢弃消息（迟到或命中已提交帧窗口），记录可定位原因。
     */
    public boolean insertDiscarded(SensorSample sample, DiscardReason reason) {
        return insert(sample, "DISCARDED", reason.name());
    }

    private boolean insert(SensorSample sample, String status, String discardReason) {
        String sql = "INSERT INTO sensor_message (sensor_type, message_id, raw_ts, corrected_ts,"
                + " drift_version, status, discard_reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            jdbc.update(sql, sample.sensorType().name(), sample.messageId(), sample.rawTs(),
                    sample.correctedTs(), sample.driftVersion(), status, discardReason, clock.millis());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
