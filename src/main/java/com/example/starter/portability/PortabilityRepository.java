package com.example.starter.portability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.portability.model.EventRecord;
import com.example.starter.portability.model.FlowRecord;
import com.example.starter.portability.model.FlowStatus;
import com.example.starter.portability.model.IdempotencyRecord;
import com.example.starter.portability.model.SubscriberProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 携号转网数据访问层：全部使用参数化 SQL。
 * 状态流转通过带状态守卫的单条 UPDATE 完成，保证撤销与生效等并发流转形成唯一终态。
 */
@Repository
public class PortabilityRepository {

    private final JdbcClient jdbc;

    public PortabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询流程。
     */
    public Optional<FlowRecord> findFlowById(long id) {
        return jdbc.sql("SELECT * FROM portability_flow WHERE id = :id")
                .param("id", id)
                .query(this::mapFlow)
                .optional();
    }

    /**
     * 按号码查询在途流程（active_key 等于号码即视为在途）。
     */
    public Optional<FlowRecord> findActiveFlowByMsisdn(String msisdn) {
        return jdbc.sql("SELECT * FROM portability_flow WHERE active_key = :msisdn")
                .param("msisdn", msisdn)
                .query(this::mapFlow)
                .optional();
    }

    /**
     * 新建在途流程，状态为 APPLIED；active_key 唯一约束保证同号唯一在途。
     *
     * @return 自增主键
     */
    public long insertFlow(String requestId, String msisdn, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO portability_flow "
                        + "(request_id, msisdn, status, active_key, version, created_at, updated_at) "
                        + "VALUES (:requestId, :msisdn, 'APPLIED', :msisdn, 0, :now, :now)")
                .param("requestId", requestId)
                .param("msisdn", msisdn)
                .param("now", Timestamp.from(now))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未获取到流程自增主键");
        }
        return key.longValue();
    }

    /**
     * 签发授权码：仅允许 APPLIED -> CODE_ISSUED。
     *
     * @return 受影响行数，0 表示状态已被并发修改
     */
    public int markCodeIssued(long id, String authCode, Instant issuedAt, Instant expiresAt, Instant now) {
        return jdbc.sql("UPDATE portability_flow SET status = 'CODE_ISSUED', auth_code = :code, "
                        + "auth_code_issued_at = :issuedAt, auth_code_expires_at = :expiresAt, "
                        + "version = version + 1, updated_at = :now "
                        + "WHERE id = :id AND status = 'APPLIED'")
                .param("code", authCode)
                .param("issuedAt", Timestamp.from(issuedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /**
     * 携出方确认：仅允许 CODE_ISSUED -> DONOR_CONFIRMED；已生效流程不受影响。
     *
     * @return 受影响行数，0 表示状态已被并发修改
     */
    public int markDonorConfirmed(long id, Instant now) {
        return jdbc.sql("UPDATE portability_flow SET status = 'DONOR_CONFIRMED', "
                        + "version = version + 1, updated_at = :now "
                        + "WHERE id = :id AND status = 'CODE_ISSUED'")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /**
     * 生效：仅允许 DONOR_CONFIRMED -> ACTIVATED，同时释放在途唯一键。
     *
     * @return 受影响行数，0 表示状态已被并发修改（如并发撤销）
     */
    public int markActivated(long id, Instant now) {
        return jdbc.sql("UPDATE portability_flow SET status = 'ACTIVATED', active_key = NULL, "
                        + "version = version + 1, updated_at = :now "
                        + "WHERE id = :id AND status = 'DONOR_CONFIRMED'")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /**
     * 撤销：仅允许非终态 -> CANCELLED，同时释放在途唯一键。
     *
     * @return 受影响行数，0 表示流程已进入终态（如并发生效）
     */
    public int markCancelled(long id, Instant now) {
        return jdbc.sql("UPDATE portability_flow SET status = 'CANCELLED', active_key = NULL, "
                        + "version = version + 1, updated_at = :now "
                        + "WHERE id = :id AND status IN ('APPLIED', 'CODE_ISSUED', 'DONOR_CONFIRMED')")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /**
     * 追加流程事件，按自增 id 排序即发生顺序。
     */
    public void insertEvent(long flowId, String eventType, String detail, Instant now) {
        jdbc.sql("INSERT INTO portability_event (flow_id, event_type, detail, created_at) "
                        + "VALUES (:flowId, :type, :detail, :now)")
                .param("flowId", flowId)
                .param("type", eventType)
                .param("detail", detail)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 按发生顺序查询流程事件。
     */
    public List<EventRecord> findEventsByFlowId(long flowId) {
        return jdbc.sql("SELECT * FROM portability_event WHERE flow_id = :flowId ORDER BY id ASC")
                .param("flowId", flowId)
                .query(this::mapEvent)
                .list();
    }

    /**
     * 查询用户资格档案。
     */
    public Optional<SubscriberProfile> findProfile(String msisdn) {
        return jdbc.sql("SELECT * FROM subscriber_profile WHERE msisdn = :msisdn")
                .param("msisdn", msisdn)
                .query(this::mapProfile)
                .optional();
    }

    /**
     * 按请求标识查询幂等记录。
     */
    public Optional<IdempotencyRecord> findIdempotency(String requestId) {
        return jdbc.sql("SELECT * FROM idempotency_record WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(this::mapIdempotency)
                .optional();
    }

    /**
     * 写入幂等记录；request_id 主键冲突时抛出 DuplicateKeyException。
     */
    public void insertIdempotency(String requestId, String endpoint, String fingerprint,
                                  int httpStatus, String responseBody, Long flowId, Instant now) {
        jdbc.sql("INSERT INTO idempotency_record "
                        + "(request_id, endpoint, fingerprint, http_status, response_body, flow_id, created_at) "
                        + "VALUES (:requestId, :endpoint, :fingerprint, :httpStatus, :body, :flowId, :now)")
                .param("requestId", requestId)
                .param("endpoint", endpoint)
                .param("fingerprint", fingerprint)
                .param("httpStatus", httpStatus)
                .param("body", responseBody)
                .param("flowId", flowId)
                .param("now", Timestamp.from(now))
                .update();
    }

    private FlowRecord mapFlow(ResultSet rs, int rowNum) throws SQLException {
        return new FlowRecord(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("msisdn"),
                FlowStatus.valueOf(rs.getString("status")),
                rs.getString("auth_code"),
                toInstant(rs.getTimestamp("auth_code_issued_at")),
                toInstant(rs.getTimestamp("auth_code_expires_at")),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private EventRecord mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new EventRecord(
                rs.getLong("id"),
                rs.getLong("flow_id"),
                rs.getString("event_type"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toInstant());
    }

    private SubscriberProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new SubscriberProfile(
                rs.getString("msisdn"),
                rs.getBoolean("has_arrears"),
                rs.getBoolean("in_contract"),
                toInstant(rs.getTimestamp("last_port_at")));
    }

    private IdempotencyRecord mapIdempotency(ResultSet rs, int rowNum) throws SQLException {
        long flowId = rs.getLong("flow_id");
        return new IdempotencyRecord(
                rs.getString("request_id"),
                rs.getString("endpoint"),
                rs.getString("fingerprint"),
                rs.getInt("http_status"),
                rs.getString("response_body"),
                rs.wasNull() ? null : flowId,
                rs.getTimestamp("created_at").toInstant());
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
