package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 切换确认响应。switched=false 表示已记录确认、尚待第二人；switched=true 表示双人确认齐全、
 * 供电组合已原子替换，旧车释放、新车占用转移并留前后容量证据。
 *
 * @param replacementId 接替单ID
 * @param status        接替单当前状态
 * @param version       接替单当前版本号
 * @param switched      本次确认后是否已完成切换
 * @param oldCapacityKw 切换前旧车容量证据，单位kW
 * @param newCapacityKw 切换后新车容量合计证据，单位kW，空表示未切换
 * @param switchedAt    切换完成时刻，空表示未切换
 */
public record SwitchConfirmResponse(long replacementId, String status, int version, boolean switched,
        int oldCapacityKw, Integer newCapacityKw, LocalDateTime switchedAt) {
}
