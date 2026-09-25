package com.example.starter.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.payment.domain.DiffType;
import com.example.starter.payment.domain.ResolveAction;
import com.example.starter.payment.dto.ChannelEntry;
import com.example.starter.payment.dto.DiffResolveRequest;
import com.example.starter.payment.dto.DiffResolveResponse;
import com.example.starter.payment.dto.DiffView;
import com.example.starter.payment.dto.ReconRequest;
import com.example.starter.payment.dto.ReconResult;
import com.example.starter.payment.repo.BatchRepository;
import com.example.starter.payment.repo.MerchantRepository;
import com.example.starter.payment.repo.ReconDiffRepository;
import com.example.starter.payment.repo.TransactionRepository;
import com.example.starter.payment.repo.TransactionRepository.TxnRow;
import com.example.starter.payment.support.BusinessException;
import com.example.starter.payment.support.TimeProvider;

/**
 * 渠道对账与差异核销服务。
 *
 * <p>以批次内本地已入账交易与渠道对账文件（合成数据）按渠道流水号逐笔勾对：
 * 本地有渠道无＝本地多账；渠道有本地无＝渠道多账；双方都有但金额不等＝差错。
 * 差异核销（核销/补记/退款）以条件更新保证同一差异只能处理一次，并追加处理留痕。</p>
 */
@Service
public class ReconService {

    private final TransactionRepository txnRepo;
    private final ReconDiffRepository diffRepo;
    private final BatchRepository batchRepo;
    private final MerchantRepository merchantRepo;
    private final TimeProvider timeProvider;

    public ReconService(TransactionRepository txnRepo, ReconDiffRepository diffRepo,
            BatchRepository batchRepo, MerchantRepository merchantRepo, TimeProvider timeProvider) {
        this.txnRepo = txnRepo;
        this.diffRepo = diffRepo;
        this.batchRepo = batchRepo;
        this.merchantRepo = merchantRepo;
        this.timeProvider = timeProvider;
    }

    /**
     * 执行对账并落差异记录；同一批次重复对账返回 409，避免重复生成差异。
     */
    @Transactional
    public ReconResult reconcile(ReconRequest request) {
        String batchDate = request.batchDate();
        if (batchRepo.find(batchDate).isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchDate);
        }
        try {
            // 每批次仅允许对账一次；唯一键同时拦截并发重复对账（即使首次无差异）。
            diffRepo.insertRun(batchDate);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "该批次已生成对账结果，不能重复对账: " + batchDate);
        }

        Map<String, TxnRow> local = new HashMap<>();
        for (TxnRow txn : txnRepo.findPostedByBatch(batchDate)) {
            local.put(txn.channelSerialNo(), txn);
        }
        Map<String, ChannelEntry> channel = new HashMap<>();
        for (ChannelEntry entry : request.entries()) {
            channel.put(entry.channelSerialNo(), entry);
        }

        int matched = 0;
        int localExtra = 0;
        int channelExtra = 0;
        int amountMismatch = 0;

        for (TxnRow txn : local.values()) {
            ChannelEntry entry = channel.get(txn.channelSerialNo());
            if (entry == null) {
                diffRepo.insert(batchDate, txn.channelSerialNo(), txn.merchantNo(), DiffType.LOCAL_EXTRA,
                        txn.amount(), null);
                localExtra++;
            } else if (txn.amount().compareTo(entry.amount()) != 0) {
                diffRepo.insert(batchDate, txn.channelSerialNo(), txn.merchantNo(), DiffType.AMOUNT_MISMATCH,
                        txn.amount(), entry.amount());
                amountMismatch++;
            } else {
                matched++;
            }
        }
        for (ChannelEntry entry : channel.values()) {
            if (!local.containsKey(entry.channelSerialNo())) {
                diffRepo.insert(batchDate, entry.channelSerialNo(), entry.merchantNo(),
                        DiffType.CHANNEL_EXTRA, null, entry.amount());
                channelExtra++;
            }
        }

        int total = matched + localExtra + channelExtra + amountMismatch;
        return new ReconResult(batchDate, total, matched, localExtra, channelExtra, amountMismatch);
    }

    @Transactional(readOnly = true)
    public List<DiffView> listDiffs(String batchDate) {
        return diffRepo.findByBatch(batchDate);
    }

    /**
     * 核销差异。动作须与差异类型匹配：本地多账→核销，渠道多账→补记，金额差错→退款。
     * 已处理差异再次提交时返回首次处理结果，不重复核销。
     */
    @Transactional
    public DiffResolveResponse resolve(long diffId, DiffResolveRequest request) {
        DiffView diff = diffRepo.lockById(diffId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "差异记录不存在: " + diffId));
        if ("RESOLVED".equals(diff.status())) {
            return new DiffResolveResponse(diff.id(), diff.channelSerialNo(), diff.diffType(),
                    diff.resolveAction(), diff.status(), true, diff.resolvedAt());
        }
        validateAction(DiffType.valueOf(diff.diffType()), request.action());

        if (request.action() == ResolveAction.SUPPLEMENT) {
            supplementLocalTxn(diff);
        }

        int updated = diffRepo.resolveIfOpen(diffId, request.action(), request.remark(), timeProvider.now());
        if (updated != 1) {
            DiffView current = diffRepo.findById(diffId).orElse(diff);
            return new DiffResolveResponse(current.id(), current.channelSerialNo(), current.diffType(),
                    current.resolveAction(), current.status(), true, current.resolvedAt());
        }
        diffRepo.insertLog(diffId, request.action(), request.operator(), request.remark());

        DiffView done = diffRepo.findById(diffId).orElseThrow();
        return new DiffResolveResponse(done.id(), done.channelSerialNo(), done.diffType(),
                done.resolveAction(), done.status(), false, done.resolvedAt());
    }

    private void validateAction(DiffType type, ResolveAction action) {
        boolean valid = switch (type) {
            case LOCAL_EXTRA -> action == ResolveAction.WRITE_OFF;
            case CHANNEL_EXTRA -> action == ResolveAction.SUPPLEMENT;
            case AMOUNT_MISMATCH -> action == ResolveAction.REFUND;
        };
        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "差异类型 " + type + " 不支持处理方式 " + action);
        }
    }

    /** 渠道多账补记：以渠道侧数据补登一笔批次内本地交易（SUPPLEMENT 来源），费率按商户当前费率快照。 */
    private void supplementLocalTxn(DiffView diff) {
        BigDecimal rate = merchantRepo.findFeeRate(diff.merchantNo())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "商户未配置手续费费率，无法补记: " + diff.merchantNo()));
        BigDecimal amount = diff.channelAmount();
        BigDecimal fee = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        String requestId = "RECON-SUPP-" + diff.channelSerialNo();
        var txn = new TxnRow(null, requestId, "SUPPLEMENT:" + diff.id(), diff.channelSerialNo(),
                diff.merchantNo(), amount, rate, fee, timeProvider.now(), diff.batchDate(),
                "POSTED", "SUPPLEMENT");
        txnRepo.insert(txn);
    }
}
