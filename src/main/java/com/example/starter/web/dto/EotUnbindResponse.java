package com.example.starter.web.dto;

/**
 * 提前解绑结果；解绑原子解除装置占用并撤销当前一致性确认，绑定历史保留。
 *
 * @param bindingId       绑定主键
 * @param deviceId        装置主键
 * @param deviceNo        装置编号
 * @param jobId           受控作业ID
 * @param released        是否已解除（恒为 true）
 * @param voidedConfirmations 本次解绑撤销的一致性确认条数
 * @param deviceVersion   解绑成功后的装置版本
 */
public record EotUnbindResponse(
        long bindingId,
        long deviceId,
        String deviceNo,
        long jobId,
        boolean released,
        int voidedConfirmations,
        int deviceVersion) {
}
