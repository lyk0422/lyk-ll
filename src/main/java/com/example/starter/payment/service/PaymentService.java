package com.example.starter.payment.service;

import com.example.starter.payment.BizException;
import com.example.starter.payment.CutoffClock;
import com.example.starter.payment.Merchant;
import com.example.starter.payment.PayBatch;
import com.example.starter.payment.PayTransaction;
import com.example.starter.payment.repo.BatchRepository;
import com.example.starter.payment.repo.MerchantRepository;
import com.example.starter.payment.repo.TransactionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 入账服务：requestId 幂等、渠道流水号去重、按 UTC 16:00 日切归属批次；
 * 批次已封账时交易进入挂账。
 */
@Service
public class PaymentService {

    private final TransactionRepository txnRepo;
    private final BatchRepository batchRepo;
    private final MerchantRepository merchantRepo;

    public PaymentService(TransactionRepository txnRepo, BatchRepository batchRepo,
                          MerchantRepository merchantRepo) {
        this.txnRepo = txnRepo;
        this.batchRepo = batchRepo;
        this.merchantRepo = merchantRepo;
    }

    /**
     * 入账请求。
     *
     * @param requestId  幂等键
     * @param serialNo   渠道流水号
     * @param merchantNo 商户号
     * @param amount     金额（分），必须为正
     * @param txnTime    交易时间（UTC）
     */
    public record PostCommand(String requestId, String serialNo, String merchantNo,
                              long amount, Instant txnTime) {
    }

    /**
     * 入账结果。
     *
     * @param txn       交易记录
     * @param batchDate 归属批次日期；挂账时为交易按日切本应归属的批次日期
     * @param replayed  是否为幂等重放（同键同参返回首次结果）
     */
    public record PostResult(PayTransaction txn, LocalDate batchDate, boolean replayed) {
    }

    @Transactional
    public PostResult post(PostCommand cmd) {
        validate(cmd);
        String fingerprint = fingerprint(cmd);

        // 幂等：同键同参返回首次结果，同键异参 409
        Optional<PayTransaction> existing = txnRepo.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            PayTransaction txn = existing.get();
            if (!txn.fingerprint().equals(fingerprint)) {
                throw BizException.conflict("requestId 已存在但请求参数不一致，拒绝重复入账");
            }
            return new PostResult(txn, CutoffClock.batchDateOf(txn.txnTime()), true);
        }

        Merchant merchant = merchantRepo.findByMerchantNo(cmd.merchantNo())
                .orElseThrow(() -> BizException.badRequest("商户不存在: " + cmd.merchantNo()));
        long fee = feeOf(cmd.amount(), merchant.feeRateBp());

        LocalDate batchDate = CutoffClock.batchDateOf(cmd.txnTime());
        // 锁定批次行：与封账串行化，保证交易只归属唯一批次
        PayBatch batch = batchRepo.lockOrCreate(batchDate);

        Long batchId;
        String status;
        if (batch.isClosed()) {
            // 封账后到达的该批次交易：拒绝入账，进入挂账
            batchId = null;
            status = PayTransaction.SUSPENDED;
        } else {
            batchId = batch.id();
            status = PayTransaction.POSTED;
        }

        try {
            PayTransaction txn = txnRepo.insert(cmd.requestId(), fingerprint, cmd.serialNo(),
                    cmd.merchantNo(), cmd.amount(), fee, cmd.txnTime(), batchId, status);
            return new PostResult(txn, batchDate, false);
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(cmd, fingerprint);
        }
    }

    /** 并发唯一键冲突后的幂等裁决。 */
    private PostResult resolveDuplicate(PostCommand cmd, String fingerprint) {
        Optional<PayTransaction> byRequest = txnRepo.findByRequestId(cmd.requestId());
        if (byRequest.isPresent()) {
            PayTransaction txn = byRequest.get();
            if (!txn.fingerprint().equals(fingerprint)) {
                throw BizException.conflict("requestId 已存在但请求参数不一致，拒绝重复入账");
            }
            return new PostResult(txn, CutoffClock.batchDateOf(txn.txnTime()), true);
        }
        if (txnRepo.findBySerialNo(cmd.serialNo()).isPresent()) {
            throw BizException.conflict("渠道流水号已入账，不允许重复: " + cmd.serialNo());
        }
        throw BizException.conflict("入账冲突，请重试");
    }

    private void validate(PostCommand cmd) {
        if (cmd.requestId() == null || cmd.requestId().isBlank()) {
            throw BizException.badRequest("requestId 不能为空");
        }
        if (cmd.serialNo() == null || cmd.serialNo().isBlank()) {
            throw BizException.badRequest("serialNo 不能为空");
        }
        if (cmd.merchantNo() == null || cmd.merchantNo().isBlank()) {
            throw BizException.badRequest("merchantNo 不能为空");
        }
        if (cmd.amount() <= 0) {
            throw BizException.badRequest("amount 必须为正数（分）");
        }
        if (cmd.txnTime() == null) {
            throw BizException.badRequest("txnTime 不能为空");
        }
    }

    /** 手续费（分）= 金额 × 费率基点 / 10000，四舍五入到分。 */
    static long feeOf(long amount, int feeRateBp) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(feeRateBp))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 幂等指纹：覆盖 requestId、流水号、金额与商户号。 */
    static String fingerprint(PostCommand cmd) {
        String material = cmd.requestId() + '|' + cmd.serialNo() + '|' + cmd.amount() + '|' + cmd.merchantNo();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
