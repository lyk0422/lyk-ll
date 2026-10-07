package com.example.starter.web.dto;

import java.util.List;

/**
 * 热备接替撤回响应：全部新车预占释放，旧车继续供电，旧到场与供电证据不覆盖。
 *
 * @param replacementId    接替单ID
 * @param status           接替单当前状态（WITHDRAWN）
 * @param version          接替单当前版本号
 * @param releasedTruckIds 已释放预占的热备新车
 */
public record WithdrawReplacementResponse(long replacementId, String status, int version,
        List<Long> releasedTruckIds) {
}
