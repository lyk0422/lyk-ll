package com.example.starter.unlock;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.error.ApiException;
import com.example.starter.unlock.UnlockDtos.UnlockRequest;
import com.example.starter.unlock.UnlockDtos.UnlockResponse;

/**
 * 紧急解锁服务：双人授权、全程留痕；
 * 解锁（UNLOCK）与闭锁恢复（RESTORE）在同一事务内成对写入，不得只解锁不恢复。
 */
@Service
public class UnlockService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UnlockService(JdbcTemplate jdbc, Clock businessClock) {
        this.jdbc = jdbc;
        this.clock = businessClock;
    }

    /**
     * 执行紧急解锁：同一事务内写入解锁与闭锁恢复两条留痕记录。
     */
    @Transactional
    public UnlockResponse unlock(UnlockRequest req) {
        if (req.authorizerA().equals(req.authorizerB())) {
            throw ApiException.badRequest("DOUBLE_AUTH_REQUIRED", "紧急解锁须双人授权，两名授权人不得相同");
        }
        Instant now = Instant.now(clock);
        long unlockId = insertRecord(req, "UNLOCK", now);
        long restoreId = insertRecord(req, "RESTORE", now);
        return new UnlockResponse(unlockId, restoreId, "RESTORED");
    }

    private long insertRecord(UnlockRequest req, String action, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO unlock_record (device_id, rule_code, authorizer_a, authorizer_b, reason, action, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.deviceId());
            ps.setString(2, req.ruleCode());
            ps.setString(3, req.authorizerA());
            ps.setString(4, req.authorizerB());
            ps.setString(5, req.reason());
            ps.setString(6, action);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
