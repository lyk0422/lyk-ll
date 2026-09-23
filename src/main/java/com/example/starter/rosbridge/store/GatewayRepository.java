package com.example.starter.rosbridge.store;

import com.example.starter.rosbridge.ros.SubscriptionKey;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 网关状态仓库：连接、上游订阅引用计数、客户端订阅、单调游标、ACK 位置、
 * Service 调用与 Action Goal 均落 H2（MODE=MySQL）。内存库，进程退出不保留。
 */
@Repository
public class GatewayRepository {

    private final JdbcTemplate jdbc;

    public GatewayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 连接 ----------------

    /** 登记一条 ACTIVE 连接，返回自增主键。 */
    public long openConnection(String clientId, String sessionId, long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ws_connection(client_id, session_id, status, connected_at) VALUES (?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, clientId);
            ps.setString(2, sessionId);
            ps.setLong(3, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 将连接置为 CLOSED；重复关闭幂等，返回是否首次关闭。 */
    public int closeConnection(String sessionId, long now) {
        return jdbc.update(
                "UPDATE ws_connection SET status = 'CLOSED', disconnected_at = ? "
                        + "WHERE session_id = ? AND status = 'ACTIVE'",
                now, sessionId);
    }

    // ---------------- 引用计数与客户端订阅 ----------------

    /** 幂等插入客户端订阅；返回 true 表示本次新插入（需要增加引用计数）。 */
    @Transactional
    public boolean insertClientSubscriptionIfAbsent(String clientId, SubscriptionKey key,
                                                    String sessionId, long now) {
        try {
            jdbc.update("INSERT INTO client_subscription"
                            + "(client_id, topic, message_type, qos, session_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    clientId, key.topic(), key.messageType(), key.qosJson(), sessionId, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 删除客户端订阅；返回 true 表示存在并删除（需要减少引用计数）。 */
    @Transactional
    public boolean deleteClientSubscription(String clientId, SubscriptionKey key) {
        return jdbc.update("DELETE FROM client_subscription WHERE client_id = ? AND topic = ? "
                + "AND message_type = ? AND qos = ?",
                clientId, key.topic(), key.messageType(), key.qosJson()) > 0;
    }

    /** 列出客户端当前全部订阅（断连时批量退订用）。 */
    public List<SubscriptionKey> listClientSubscriptions(String clientId) {
        return jdbc.query("SELECT topic, message_type, qos FROM client_subscription WHERE client_id = ?",
                (rs, n) -> new SubscriptionKey(rs.getString(1), rs.getString(2), rs.getString(3)),
                clientId);
    }

    /** 列出某共享订阅的全部客户端订阅者（上游消息扇出用）。 */
    public List<String> listSubscribers(SubscriptionKey key) {
        return jdbc.queryForList("SELECT client_id FROM client_subscription "
                        + "WHERE topic = ? AND message_type = ? AND qos = ?",
                String.class, key.topic(), key.messageType(), key.qosJson());
    }

    /** 确保共享订阅引用行存在（refcount 初始 0）。 */
    @Transactional
    public void ensureSubscriptionRef(SubscriptionKey key) {
        try {
            jdbc.update("INSERT INTO subscription_ref(topic, message_type, qos, refcount) VALUES (?, ?, ?, 0)",
                    key.topic(), key.messageType(), key.qosJson());
        } catch (DuplicateKeyException e) {
            // 已存在，保持原引用计数
        }
    }

    /** 引用计数 +1 并清除宽限截止时间，返回更新后的引用计数。 */
    @Transactional
    public int incrementRefcount(SubscriptionKey key) {
        jdbc.update("UPDATE subscription_ref SET refcount = refcount + 1, grace_until = NULL "
                        + "WHERE topic = ? AND message_type = ? AND qos = ?",
                key.topic(), key.messageType(), key.qosJson());
        return getRefcount(key);
    }

    /** 引用计数 -1 并进入宽限期，返回更新后的引用计数。 */
    @Transactional
    public int decrementRefcount(SubscriptionKey key, long graceUntil) {
        jdbc.update("UPDATE subscription_ref SET refcount = refcount - 1, grace_until = ? "
                        + "WHERE topic = ? AND message_type = ? AND qos = ? AND refcount > 0",
                graceUntil, key.topic(), key.messageType(), key.qosJson());
        return getRefcount(key);
    }

    public int getRefcount(SubscriptionKey key) {
        List<Integer> rows = jdbc.queryForList("SELECT refcount FROM subscription_ref "
                + "WHERE topic = ? AND message_type = ? AND qos = ?",
                Integer.class, key.topic(), key.messageType(), key.qosJson());
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** 仅当引用计数为 0 时删除引用行（宽限结束释放），返回是否删除。 */
    @Transactional
    public boolean deleteRefIfZero(SubscriptionKey key) {
        return jdbc.update("DELETE FROM subscription_ref WHERE topic = ? AND message_type = ? AND qos = ? "
                + "AND refcount = 0", key.topic(), key.messageType(), key.qosJson()) > 0;
    }

    // ---------------- 游标与 ACK ----------------

    /** 分配共享订阅的下一个单调游标（从 1 开始），事务内自增保证唯一递增。 */
    @Transactional
    public long nextCursor(SubscriptionKey key) {
        ensureCursorRow(key);
        jdbc.update("UPDATE subscription_cursor SET last_cursor = last_cursor + 1 "
                        + "WHERE topic = ? AND message_type = ? AND qos = ?",
                key.topic(), key.messageType(), key.qosJson());
        Long v = jdbc.queryForObject("SELECT last_cursor FROM subscription_cursor "
                        + "WHERE topic = ? AND message_type = ? AND qos = ?",
                Long.class, key.topic(), key.messageType(), key.qosJson());
        return v == null ? 0 : v;
    }

    public long getLastCursor(SubscriptionKey key) {
        ensureCursorRow(key);
        Long v = jdbc.queryForObject("SELECT last_cursor FROM subscription_cursor "
                        + "WHERE topic = ? AND message_type = ? AND qos = ?",
                Long.class, key.topic(), key.messageType(), key.qosJson());
        return v == null ? 0 : v;
    }

    private void ensureCursorRow(SubscriptionKey key) {
        try {
            jdbc.update("INSERT INTO subscription_cursor(topic, message_type, qos, last_cursor) VALUES (?, ?, ?, 0)",
                    key.topic(), key.messageType(), key.qosJson());
        } catch (DuplicateKeyException e) {
            // 已存在
        }
    }

    /** 查询某 Topic 全部已知共享游标键（ACK 帧未带 type/qos 时兜底）。 */
    public List<SubscriptionKey> listCursorKeysForTopic(String topic) {
        return jdbc.query("SELECT topic, message_type, qos FROM subscription_cursor WHERE topic = ?",
                (rs, n) -> new SubscriptionKey(rs.getString(1), rs.getString(2), rs.getString(3)),
                topic);
    }

    /** 记录客户端 ACK 位置（仅前进，不回退），返回更新后的 ACK 游标。 */
    @Transactional
    public long ackCursor(String clientId, SubscriptionKey key, long cursor, long now) {
        Long current = queryAck(clientId, key);
        if (current == null) {
            try {
                jdbc.update("INSERT INTO client_cursor(client_id, topic, message_type, qos, ack_cursor, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        clientId, key.topic(), key.messageType(), key.qosJson(), cursor, now);
            } catch (DuplicateKeyException e) {
                updateAckIfGreater(clientId, key, cursor, now);
            }
        } else if (cursor > current) {
            updateAckIfGreater(clientId, key, cursor, now);
        }
        Long v = queryAck(clientId, key);
        return v == null ? 0 : v;
    }

    private void updateAckIfGreater(String clientId, SubscriptionKey key, long cursor, long now) {
        jdbc.update("UPDATE client_cursor SET ack_cursor = CASE WHEN ? > ack_cursor THEN ? ELSE ack_cursor END, "
                        + "updated_at = ? WHERE client_id = ? AND topic = ? AND message_type = ? AND qos = ?",
                cursor, cursor, now, clientId, key.topic(), key.messageType(), key.qosJson());
    }

    /** 查询客户端已确认游标；从未 ACK 返回 null。 */
    public Long queryAck(String clientId, SubscriptionKey key) {
        List<Long> list = jdbc.query("SELECT ack_cursor FROM client_cursor "
                        + "WHERE client_id = ? AND topic = ? AND message_type = ? AND qos = ?",
                (rs, n) -> rs.getLong(1),
                clientId, key.topic(), key.messageType(), key.qosJson());
        return list.isEmpty() ? null : list.get(0);
    }

    // ---------------- Service 调用 ----------------

    /** 幂等登记 Service 调用；返回 true 表示首次（PENDING），false 表示同 (clientId,requestId) 已存在。 */
    @Transactional
    public boolean insertCallIfAbsent(String clientId, String requestId, String service, long now) {
        try {
            jdbc.update("INSERT INTO service_call(client_id, request_id, service, status, created_at) "
                            + "VALUES (?, ?, ?, 'PENDING', ?)",
                    clientId, requestId, service, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** PENDING -> 期望新状态的比较交换；迟到结果/重复超时会 CAS 失败，保证请求只完成一次。 */
    @Transactional
    public boolean casCall(String clientId, String requestId, String newStatus,
                           String resultJson, String error, long now) {
        return jdbc.update("UPDATE service_call SET status = ?, result_json = ?, error_msg = ?, completed_at = ? "
                        + "WHERE client_id = ? AND request_id = ? AND status = 'PENDING'",
                newStatus, resultJson, error, now, clientId, requestId) > 0;
    }

    public String getCallStatus(String clientId, String requestId) {
        return jdbc.queryForObject("SELECT status FROM service_call WHERE client_id = ? AND request_id = ?",
                String.class, clientId, requestId);
    }

    // ---------------- Action Goal ----------------

    /** 幂等登记 Goal；返回 true 表示首次（PENDING）。 */
    @Transactional
    public boolean insertGoalIfAbsent(String clientId, String goalId, String action, long now) {
        try {
            jdbc.update("INSERT INTO action_goal(client_id, goal_id, action, status, created_at) "
                            + "VALUES (?, ?, ?, 'PENDING', ?)",
                    clientId, goalId, action, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** PENDING -> 终态的比较交换；取消与适配器最终结果竞争时只有一方成功。 */
    @Transactional
    public boolean casGoal(String clientId, String goalId, String newStatus,
                           String resultJson, String error, long now) {
        return jdbc.update("UPDATE action_goal SET status = ?, result_json = ?, error_msg = ?, completed_at = ? "
                        + "WHERE client_id = ? AND goal_id = ? AND status = 'PENDING'",
                newStatus, resultJson, error, now, clientId, goalId) > 0;
    }

    public String getGoalStatus(String clientId, String goalId) {
        return jdbc.queryForObject("SELECT status FROM action_goal WHERE client_id = ? AND goal_id = ?",
                String.class, clientId, goalId);
    }

    /** 清理全部状态（测试场景间使用）。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM client_subscription");
        jdbc.update("DELETE FROM subscription_ref");
        jdbc.update("DELETE FROM subscription_cursor");
        jdbc.update("DELETE FROM client_cursor");
        jdbc.update("DELETE FROM service_call");
        jdbc.update("DELETE FROM action_goal");
        jdbc.update("DELETE FROM ws_connection");
    }
}
