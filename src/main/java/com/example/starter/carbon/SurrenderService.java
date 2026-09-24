package com.example.starter.carbon;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.carbon.dto.SubmitSurrenderRequest;
import com.example.starter.carbon.dto.SurrenderItemView;
import com.example.starter.carbon.dto.SurrenderResponse;
import com.example.starter.carbon.model.AccountRow;
import com.example.starter.carbon.model.BatchRow;
import com.example.starter.carbon.model.ItemRow;
import com.example.starter.carbon.model.RequestRow;

/**
 * 清缴与抵消核销核心服务。
 *
 * <p>规则要点：
 * <ul>
 *   <li>抵消信用累计抵减不得超过核定排放量的 5%，恰好用满合法，超出拒绝整笔申请；</li>
 *   <li>配额与信用均按“到期日近者优先，到期日相同按签发先后（先进先出）”扣减；</li>
 *   <li>单笔申请内组合扣减原子：任一步余额不足整笔回滚，账户无变动；</li>
 *   <li>requestId 幂等：同键同参返回首次注销清单，同键异参返回 409 且账户不变；</li>
 *   <li>同一企业同一履约年度的申请按到达顺序串行生效（进程内分段锁 + 数据库行锁）；</li>
 *   <li>以请求到达时刻（UTC）与截止时刻比较：恰在截止时刻仍受理，之后一律拒绝。</li>
 * </ul>
 */
@Service
public class SurrenderService {

    /** 抵消信用抵减上限：排放量的 5%。 */
    private static final BigDecimal OFFSET_CAP_RATIO = new BigDecimal("0.05");

    private final CarbonRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    public SurrenderService(CarbonRepository repository,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 受理清缴申请。到达时刻在进入串行区之前采样，保证截止判定的唯一口径。
     */
    public SurrenderResponse submit(SubmitSurrenderRequest request) {
        Instant arrivedAt = clock.instant();
        validateAmounts(request);
        String fingerprint = fingerprint(request);
        ReentrantLock lock = accountLocks.computeIfAbsent(
                request.enterpriseId() + ":" + request.complianceYear(), key -> new ReentrantLock());
        lock.lock();
        try {
            return transactionTemplate.execute(status -> doSubmit(request, fingerprint, arrivedAt));
        } catch (DuplicateKeyException duplicate) {
            // 唯一约束兜底：并发下同 requestId 落库冲突，按幂等规则重读判定。
            return resolveDuplicate(request, fingerprint);
        } finally {
            lock.unlock();
        }
    }

    private SurrenderResponse doSubmit(SubmitSurrenderRequest request, String fingerprint,
            Instant arrivedAt) {
        var existing = repository.findRequest(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        AccountRow account = repository
                .findAccountForUpdate(request.enterpriseId(), request.complianceYear())
                .orElseThrow(() -> ApiException.notFound(
                        "COMPLIANCE_ACCOUNT_NOT_FOUND", "该企业该履约年度尚未核定排放量"));

        // 截止口径：恰在截止时刻（等于）仍受理，严格晚于截止时刻才拒绝。
        if (arrivedAt.isAfter(account.deadlineAt())) {
            repository.markNonCompliant(request.enterpriseId(), request.complianceYear());
            throw ApiException.unprocessable("SURRENDER_OVERDUE", "已超过履约截止时刻，逾期申请一律拒绝");
        }
        if ("NON_COMPLIANT".equals(account.status())) {
            throw ApiException.unprocessable("SURRENDER_OVERDUE", "该履约年度已标记未履约，申请拒绝");
        }
        if ("SURRENDERED".equals(account.status())) {
            throw ApiException.unprocessable("ALREADY_SURRENDERED", "该履约年度已足额清缴，无需重复申请");
        }

        // 5% 抵减上限（累计口径）：恰好用满合法，超出拒绝整笔申请。
        BigDecimal offsetAfter = BigDecimal.valueOf(account.surrenderedOffset() + request.offsetAmount());
        BigDecimal cap = BigDecimal.valueOf(account.emissions()).multiply(OFFSET_CAP_RATIO);
        if (offsetAfter.compareTo(cap) > 0) {
            throw ApiException.unprocessable(
                    "OFFSET_CAP_EXCEEDED", "抵消信用抵减超过核定排放量的 5% 上限，整笔申请拒绝");
        }

        // 足额口径：履约义务按累计判定，允许部分清缴；累计达到排放量即转入已清缴终态。
        long newAllowance = account.surrenderedAllowance() + request.allowanceAmount();
        long newOffset = account.surrenderedOffset() + request.offsetAmount();

        List<ItemRow> items = new ArrayList<>();
        deduct(request.requestId(), "OFFSET",
                repository.findOffsetBatchesForUpdate(request.enterpriseId(), request.complianceYear()),
                request.offsetAmount(), items, true);
        deduct(request.requestId(), "ALLOWANCE",
                repository.findAllowanceBatchesForUpdate(request.enterpriseId(), request.complianceYear()),
                request.allowanceAmount(), items, false);

        RequestRow row = new RequestRow(
                request.requestId(), fingerprint, request.enterpriseId(), request.complianceYear(),
                request.allowanceAmount(), request.offsetAmount(), newCertificateNo(), arrivedAt);
        repository.insertRequest(row);
        items.forEach(repository::insertItem);
        String newStatus = newAllowance + newOffset >= account.emissions() ? "SURRENDERED" : "PENDING";
        repository.updateAccountAfterSurrender(
                request.enterpriseId(), request.complianceYear(), newAllowance, newOffset, newStatus);
        return toResponse(row, items);
    }

    /**
     * 按“到期日近者优先＋先进先出”顺序扣减；任一批次余额不足即抛错，事务回滚保证组合原子性。
     */
    private void deduct(String requestId, String sourceType, List<BatchRow> batches,
            long amount, List<ItemRow> items, boolean offset) {
        long needed = amount;
        for (BatchRow batch : batches) {
            if (needed == 0) {
                break;
            }
            long take = Math.min(batch.quantityRemaining(), needed);
            int updated = offset
                    ? repository.deductOffset(batch.id(), take)
                    : repository.deductAllowance(batch.id(), take);
            if (updated == 0) {
                throw ApiException.unprocessable(
                        "INSUFFICIENT_BALANCE", "批次余额不足，整笔申请拒绝且账户无变动");
            }
            items.add(new ItemRow(requestId, sourceType, batch.id(), take));
            needed -= take;
        }
        if (needed > 0) {
            throw ApiException.unprocessable(
                    "INSUFFICIENT_BALANCE",
                    ("OFFSET".equals(sourceType) ? "抵消信用" : "配额") + "可用余额不足，整笔申请拒绝且账户无变动");
        }
    }

    /**
     * 同键同参返回首次注销清单；同键异参返回 409 且账户不变。
     */
    private SurrenderResponse replayOrConflict(RequestRow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict(
                    "IDEMPOTENT_CONFLICT", "相同 requestId 的申请明细不一致，账户未发生变动");
        }
        return toResponse(existing, repository.findItems(existing.requestId()));
    }

    private SurrenderResponse resolveDuplicate(SubmitSurrenderRequest request, String fingerprint) {
        return repository.findRequest(request.requestId())
                .map(row -> replayOrConflict(row, fingerprint))
                .orElseThrow(() -> ApiException.conflict(
                        "IDEMPOTENT_CONFLICT", "相同 requestId 的申请处理冲突，请重试"));
    }

    /**
     * 凭证查询：凭证号唯一，重复查询结果稳定。
     */
    public SurrenderResponse getSurrender(String requestId) {
        RequestRow row = repository.findRequest(requestId)
                .orElseThrow(() -> ApiException.notFound("SURRENDER_NOT_FOUND", "清缴申请不存在"));
        return toResponse(row, repository.findItems(requestId));
    }

    private SurrenderResponse toResponse(RequestRow row, List<ItemRow> items) {
        List<SurrenderItemView> views = items.stream()
                .map(item -> new SurrenderItemView(item.sourceType(), item.batchId(), item.quantity()))
                .toList();
        return new SurrenderResponse(
                row.requestId(), row.enterpriseId(), row.complianceYear(),
                row.allowanceAmount(), row.offsetAmount(), row.certificateNo(), row.createdAt(), views);
    }

    private void validateAmounts(SubmitSurrenderRequest request) {
        if (request.allowanceAmount() + request.offsetAmount() <= 0) {
            throw ApiException.badRequest("EMPTY_SURRENDER", "配额与抵消信用组合量必须大于 0");
        }
    }

    private String newCertificateNo() {
        return "CERT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    /**
     * 幂等指纹：覆盖 requestId、企业、履约年度与申请的组合明细。
     */
    private String fingerprint(SubmitSurrenderRequest request) {
        String material = String.join("|",
                request.requestId(),
                String.valueOf(request.enterpriseId()),
                String.valueOf(request.complianceYear()),
                String.valueOf(request.allowanceAmount()),
                String.valueOf(request.offsetAmount()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
