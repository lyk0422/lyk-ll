package com.example.starter.hazmat.web;

import com.example.starter.hazmat.domain.CheckinResult;
import java.time.LocalDateTime;

/**
 * 打卡结果视图；逆序拒绝时 accepted=false 并附带告警说明。
 */
public record CheckinView(
        String requestId,
        long waybillId,
        int waypointSeq,
        LocalDateTime checkinTime,
        boolean accepted,
        CheckinResult result,
        String message) {
}
