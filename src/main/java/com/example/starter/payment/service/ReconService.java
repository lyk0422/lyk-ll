package com.example.starter.payment.service;

import com.example.starter.payment.BizException;
import com.example.starter.payment.PayBatch;
import com.example.starter.payment.PayTransaction;
import com.example.starter.payment.ReconDifference;
import com.example.starter.payment.repo.BatchRepository;
import com.example.starter.payment.repo.DifferenceRepository;
import com.example.starter.payment.repo.TransactionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对账服务：以渠道对账文件（合成数据）逐笔勾对，生成三类差异并支持处理留痕。
 */
@Service
public class ReconService {

    private final BatchRepository batchRepo;
    private final TransactionRepository txnRepo;
    private final DifferenceRepository diffRepo;

    public ReconService(BatchRepository batchRepo, TransactionRepository txnRepo,
                        DifferenceRepository diffRepo) {
        this.batchRepo = batchRepo;
        this.txnRepo = txnRepo;
        this.diffRepo = diffRepo;
    }

    /** 渠道对账文件条目（合成数据）。 */
    public record ChannelEntry(String serialNo, long amount) {
    }

    /**
     * 对指定批次执行对账。
     *
     * @param batchDate 批次日期
     * @param entries   渠道对账文件条目
     * @return 本次对账后该批次的全部差异记录
     */
    @Transactional
    public List<ReconDifference> reconcile(LocalDate batchDate, List<ChannelEntry> entries) {
        PayBatch batch = batchRepo.lockOrCreate(batchDate);
        List<PayTransaction> localTxns = txnRepo.findPostedByBatchId(batch.id());

        Map<String, PayTransaction> localBySerial = new HashMap<>();
        for (PayTransaction txn : localTxns) {
            localBySerial.put(txn.serialNo(), txn);
        }
        Map<String, Long> channelBySerial = new HashMap<>();
        for (ChannelEntry entry : entries) {
            if (entry.serialNo() == null || entry.serialNo().isBlank()) {
                throw BizException.badRequest("渠道对账条目 serialNo 不能为空");
            }
            channelBySerial.putIfAbsent(entry.serialNo(), entry.amount());
        }

        List<ReconDifference> created = new ArrayList<>();
        // 本地有、渠道无 → 本地多账；两边都有但金额不一致 → 差错
        for (PayTransaction txn : localTxns) {
            Long channelAmount = channelBySerial.get(txn.serialNo());
            if (channelAmount == null) {
                created.add(insertDiff(batch.id(), txn.serialNo(), ReconDifference.LOCAL_EXCESS,
                        txn.amount(), null));
            } else if (channelAmount != txn.amount()) {
                created.add(insertDiff(batch.id(), txn.serialNo(), ReconDifference.AMOUNT_MISMATCH,
                        txn.amount(), channelAmount));
            }
        }
        // 渠道有、本地无 → 渠道多账
        Set<String> localSerials = localBySerial.keySet();
        for (Map.Entry<String, Long> entry : channelBySerial.entrySet()) {
            if (!localSerials.contains(entry.getKey())) {
                created.add(insertDiff(batch.id(), entry.getKey(), ReconDifference.CHANNEL_EXCESS,
                        null, entry.getValue()));
            }
        }
        return diffRepo.findByBatchId(batch.id());
    }

    /** 插入差异；同批次同流水号重复对账时忽略（唯一约束兜底）。 */
    private ReconDifference insertDiff(long batchId, String serialNo, String diffType,
                                       Long localAmount, Long channelAmount) {
        try {
            return diffRepo.insert(batchId, serialNo, diffType, localAmount, channelAmount);
        } catch (DuplicateKeyException e) {
            return diffRepo.findByBatchId(batchId).stream()
                    .filter(d -> d.serialNo().equals(serialNo))
                    .findFirst()
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 处理差异（核销/补记/退款），同一差异不允许重复处理；处理结果留痕。
     *
     * @param differenceId 差异ID
     * @param resolution   WRITE_OFF 核销 / REPOST 补记 / REFUND 退款
     * @param operator     处理人
     */
    @Transactional
    public ReconDifference resolve(long differenceId, String resolution, String operator) {
        if (!Set.of(ReconDifference.WRITE_OFF, ReconDifference.REPOST, ReconDifference.REFUND)
                .contains(resolution)) {
            throw BizException.badRequest("非法处理方式: " + resolution);
        }
        if (operator == null || operator.isBlank()) {
            throw BizException.badRequest("处理人不能为空");
        }
        int updated = diffRepo.markResolved(differenceId, resolution, operator);
        if (updated == 0) {
            diffRepo.findById(differenceId)
                    .orElseThrow(() -> BizException.notFound("差异不存在: " + differenceId));
            throw BizException.conflict("差异已处理，不允许重复核销: " + differenceId);
        }
        diffRepo.insertResolutionLog(differenceId, resolution, operator);
        return diffRepo.findById(differenceId)
                .orElseThrow(() -> new IllegalStateException("差异读取失败: " + differenceId));
    }

    @Transactional(readOnly = true)
    public List<ReconDifference> differencesOf(LocalDate batchDate) {
        return batchRepo.findByDate(batchDate)
                .map(batch -> diffRepo.findByBatchId(batch.id()))
                .orElse(List.of());
    }
}
