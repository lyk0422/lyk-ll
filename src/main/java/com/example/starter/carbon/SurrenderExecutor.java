package com.example.starter.carbon;

import com.example.starter.carbon.ApiDtos.SurrenderApplyRequest;
import com.example.starter.carbon.ApiDtos.SurrenderResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 清缴事务执行器：在单事务内完成幂等复核、截止判定、抵减上限校验、
 * 配额与抵消信用的组合原子扣减、注销明细落库与凭证签发。
 * 任一规则不满足即抛异常回滚，账户不发生任何变动。
 */
@Component
public class SurrenderExecutor {

    private final CarbonRepository repo;
    private final CarbonProperties properties;

    public SurrenderExecutor(CarbonRepository repo, CarbonProperties properties) {
        this.repo = repo;
        this.properties = properties;
    }

    /**
     * 在调用方持有的（企业， 履约年度）串行锁内执行，事务边界即原子边界。
     *
     * @param arrival 请求到达时刻（进入服务时读取的应用时钟），用于截止判定
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SurrenderResponse execute(SurrenderApplyRequest req, long enterpriseId,
                                     String fingerprint, Instant arrival) {
        // 锁内幂等复核：并发下同 requestId 只有先到者落库。
        var existing = repo.findRequestByRequestId(req.requestId());
        if (existing.isPresent()) {
            CarbonRepository.RequestRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("REQUEST_ID_CONFLICT",
                        "requestId 已存在但申请明细不一致: " + row.requestId());
            }
            return SurrenderService.buildResponse(repo, row, true);
        }

        // 截止判定：恰在截止时刻到达仍受理，之后一律拒绝。
        if (arrival.isAfter(properties.deadline())) {
            throw ApiException.unprocessable("COMPLIANCE_DEADLINE_PASSED",
                    "履约截止时刻已过，逾期申请一律拒绝: " + properties.deadline());
        }

        long emission = repo.findEmission(enterpriseId, req.complianceYear())
                .orElseThrow(() -> ApiException.unprocessable("EMISSION_NOT_ASSESSED",
                        "该年度尚未核定排放量，无法清缴: " + req.complianceYear()));

        // 抵减上限：累计抵消量不得超过核定排放量的 5%，恰好用满 5% 合法。
        long offsetAfter = repo.sumOffsetUsed(enterpriseId, req.complianceYear()) + req.offsetAmount();
        if (offsetAfter * 20 > emission) {
            throw ApiException.unprocessable("OFFSET_CAP_EXCEEDED",
                    "抵消信用累计抵减超过核定排放量 5% 上限，整笔拒绝");
        }

        // 累计清缴不得超过核定排放量。
        long totalAfter = repo.sumSurrenderedTotal(enterpriseId, req.complianceYear())
                + req.allowanceAmount() + req.offsetAmount();
        if (totalAfter > emission) {
            throw ApiException.unprocessable("EXCEEDS_EMISSION",
                    "累计清缴量将超过核定排放量，整笔拒绝");
        }

        // 组合原子扣减：先配额后抵消，任一步余额不足抛异常回滚，账户无变动。
        List<long[]> allowanceItems = deduct(
                repo.lockAllowanceBatches(enterpriseId), req.allowanceAmount(),
                "INSUFFICIENT_ALLOWANCE", "配额余额不足，整笔拒绝", true);
        List<long[]> offsetItems = deduct(
                repo.lockOffsetBatches(enterpriseId), req.offsetAmount(),
                "INSUFFICIENT_OFFSET", "抵消信用余额不足，整笔拒绝", false);

        long requestPk = repo.insertRequest(req.requestId(), enterpriseId, req.complianceYear(),
                fingerprint, req.allowanceAmount(), req.offsetAmount());
        for (long[] item : allowanceItems) {
            repo.insertItem(requestPk, "ALLOWANCE", item[0], item[1]);
        }
        for (long[] item : offsetItems) {
            repo.insertItem(requestPk, "OFFSET", item[0], item[1]);
        }

        // 签发清缴凭证：凭证号由履约年度与申请序号派生，全局唯一且稳定。
        String certificateNo = "CT" + req.complianceYear() + "-" + String.format("%08d", requestPk);
        repo.insertCertificate(certificateNo, requestPk, enterpriseId, req.complianceYear(),
                req.allowanceAmount() + req.offsetAmount(), req.offsetAmount());

        // 足额履约后状态推进为 COMPLIANT。
        if (totalAfter >= emission) {
            repo.upsertComplianceStatus(enterpriseId, req.complianceYear());
            repo.updateComplianceStatus(enterpriseId, req.complianceYear(), "COMPLIANT");
        }

        CarbonRepository.RequestRow row = new CarbonRepository.RequestRow(requestPk, req.requestId(),
                enterpriseId, req.complianceYear(), fingerprint, req.allowanceAmount(),
                req.offsetAmount(), "COMPLETED");
        return SurrenderService.buildResponse(repo, row, false);
    }

    /**
     * 按“到期日近者优先，到期日相同则先入库者优先”顺序扣减批次。
     * 批次已在事务内 SELECT ... FOR UPDATE 锁定；余额不足抛异常触发整体回滚。
     *
     * @return 扣减明细列表，元素为 {batchId, amount}
     */
    private List<long[]> deduct(List<CarbonRepository.BatchRow> batches, long amount,
                                String errorCode, String errorMessage, boolean allowance) {
        if (amount == 0) {
            return List.of();
        }
        long available = batches.stream().mapToLong(CarbonRepository.BatchRow::remainingAmount).sum();
        if (available < amount) {
            throw ApiException.unprocessable(errorCode, errorMessage);
        }
        List<long[]> items = new ArrayList<>();
        long remaining = amount;
        for (CarbonRepository.BatchRow batch : batches) {
            if (remaining == 0) {
                break;
            }
            long take = Math.min(batch.remainingAmount(), remaining);
            if (take <= 0) {
                continue;
            }
            long left = batch.remainingAmount() - take;
            if (allowance) {
                repo.updateAllowanceRemaining(batch.id(), left);
            } else {
                repo.updateOffsetRemaining(batch.id(), left);
            }
            items.add(new long[]{batch.id(), take});
            remaining -= take;
        }
        return items;
    }
}
