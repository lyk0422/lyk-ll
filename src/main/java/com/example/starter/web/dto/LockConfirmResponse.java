package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

/**
 * 锁阀／复开双人确认结果。
 *
 * @param effective        操作是否生效（两名不同操作人方为 true，单人/同人不生效）
 * @param injectionLocked  操作后加注阀锁定状态
 * @param alarmActive      操作后告警状态
 * @param idempotentReplay 是否为同键同参重放
 */
public record LockConfirmResponse(
        boolean effective,
        boolean injectionLocked,
        boolean alarmActive,
        boolean idempotentReplay) implements Replayable<LockConfirmResponse> {

    @Override
    public LockConfirmResponse withReplay(boolean replay) {
        return new LockConfirmResponse(effective, injectionLocked, alarmActive, replay);
    }
}
