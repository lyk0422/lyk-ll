package com.example.starter.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 配方投产请求：将已批准（冻结）版本投产到目标养殖批次，并记录末次含药饲喂时刻。
 *
 * <p>含药配方投产要求批次动物种类与配方目标种类一致；投产即生成批准版本的药物快照，
 * 后续配方新版本修改不追溯减轻已产生的休药期义务。</p>
 *
 * @param formulaCode 已批准配方编码
 * @param version     已批准版本号
 * @param batchNo     养殖批次号
 * @param feedAtUtc   末次饲喂该含药饲料的时刻（UTC）；休药期＝该时刻（UTC 日界）＋药物休药天数
 */
public record ProductionRequest(
        @NotBlank(message = "配方编码不能为空")
        @Size(max = 64)
        String formulaCode,

        @Positive(message = "版本号必须为正数")
        int version,

        @NotBlank(message = "批次号不能为空")
        @Size(max = 64)
        String batchNo,

        @NotNull(message = "末次含药饲喂时刻不能为空")
        @PastOrPresent(message = "末次含药饲喂时刻不能晚于当前时刻")
        Instant feedAtUtc
) {
}
