package com.example.starter.invoice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.invoice.domain.InvoiceDirection;
import com.example.starter.invoice.domain.InvoiceStatus;
import com.example.starter.invoice.repo.CreditAccountRepository;
import com.example.starter.invoice.repo.CreditAccountRow;
import com.example.starter.invoice.repo.CreditLedgerRepository;
import com.example.starter.invoice.repo.InvoiceRepository;
import com.example.starter.invoice.repo.InvoiceRow;
import com.example.starter.invoice.web.ApiException;
import com.example.starter.invoice.web.IssueInvoiceRequest;
import com.example.starter.invoice.web.InvoiceLineRequest;
import com.example.starter.invoice.web.InvoiceResponse;

/**
 * 蓝字发票服务：开具（幂等＋额度串行校验）、交付、归档与查询。
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          CreditAccountRepository creditAccountRepository,
                          CreditLedgerRepository creditLedgerRepository,
                          Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.creditLedgerRepository = creditLedgerRepository;
        this.clock = clock;
    }

    /**
     * 开具蓝字发票。以 requestId 幂等：同键同参返回首次结果，同键异参返回 409 且额度不变；
     * 可用额度恰等于开票金额合法，超出拒绝且额度不变；并发通过账户行锁串行校验。
     */
    @Transactional
    public InvoiceResponse issue(IssueInvoiceRequest request) {
        String fingerprint = issueFingerprint(request);
        var existing = invoiceRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            InvoiceRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.idempotencyConflict("相同 requestId 的业务字段不一致");
            }
            return toResponse(row, true);
        }

        boolean buyerBooked = Boolean.TRUE.equals(request.buyerBooked());
        BigDecimal totalAmount = validateAndSumLines(request.lines());
        String invoiceNo = request.invoiceNo() == null || request.invoiceNo().isBlank()
                ? generateNo("B") : request.invoiceNo();

        LocalDateTime now = LocalDateTime.now(clock);
        CreditAccountRow account = creditAccountRepository.lockBySellerTaxNo(request.sellerTaxNo())
                .orElseThrow(() -> ApiException.businessRule("销方无授信账户：" + request.sellerTaxNo()));
        BigDecimal available = account.available();
        if (totalAmount.compareTo(available) > 0) {
            throw ApiException.businessRule("可用额度不足：可用 " + available + "，申请 " + totalAmount);
        }

        long invoiceId;
        try {
            invoiceId = invoiceRepository.insert(request.requestId(), fingerprint, invoiceNo,
                    InvoiceDirection.BLUE, InvoiceStatus.ISSUED, request.sellerTaxNo(), request.buyerTaxNo(),
                    buyerBooked, totalAmount, null, null, now);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 或同发票号码冲突：优先按幂等键返回首次结果
            return resolveDuplicate(request.requestId(), fingerprint);
        }
        int lineNo = 1;
        for (InvoiceLineRequest line : request.lines()) {
            invoiceRepository.insertLine(invoiceId, lineNo++, line.itemName(),
                    line.quantity(), line.unitPrice(), line.amount());
        }

        BigDecimal newBlueUnarchived = account.blueUnarchivedAmount().add(totalAmount);
        creditAccountRepository.updateAmounts(account.id(), newBlueUnarchived,
                account.redConfirmedAmount(), now);
        creditLedgerRepository.append(account.sellerTaxNo(), "ISSUE", invoiceNo,
                totalAmount, BigDecimal.ZERO, newBlueUnarchived, account.redConfirmedAmount(), now);

        return toResponse(invoiceRepository.findById(invoiceId).orElseThrow(), false);
    }

    /**
     * 交付发票：仅已开具可交付；重复交付返回幂等结果。
     */
    @Transactional
    public InvoiceResponse deliver(String invoiceNo) {
        InvoiceRow invoice = lockInvoice(invoiceNo);
        if (invoice.status() == InvoiceStatus.DELIVERED) {
            return toResponse(invoice, true);
        }
        if (invoice.status() != InvoiceStatus.ISSUED) {
            throw ApiException.businessRule("当前状态不允许交付：" + invoice.status());
        }
        invoiceRepository.updateStatus(invoice.id(), InvoiceStatus.DELIVERED, LocalDateTime.now(clock));
        return toResponse(invoiceRepository.findById(invoice.id()).orElseThrow(), false);
    }

    /**
     * 归档发票：终态。重复归档返回幂等成功；归档后额度占用释放（未归档蓝票金额减少）。
     */
    @Transactional
    public InvoiceResponse archive(String invoiceNo) {
        InvoiceRow invoice = lockInvoice(invoiceNo);
        if (invoice.status() == InvoiceStatus.ARCHIVED) {
            return toResponse(invoice, true);
        }
        if (invoice.status() != InvoiceStatus.DELIVERED) {
            throw ApiException.businessRule("当前状态不允许归档：" + invoice.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        invoiceRepository.updateStatus(invoice.id(), InvoiceStatus.ARCHIVED, now);

        CreditAccountRow account = creditAccountRepository.lockBySellerTaxNo(invoice.sellerTaxNo())
                .orElseThrow(() -> ApiException.businessRule("销方无授信账户：" + invoice.sellerTaxNo()));
        BigDecimal newBlueUnarchived = account.blueUnarchivedAmount().subtract(invoice.totalAmount());
        creditAccountRepository.updateAmounts(account.id(), newBlueUnarchived,
                account.redConfirmedAmount(), now);
        creditLedgerRepository.append(account.sellerTaxNo(), "ARCHIVE", invoiceNo,
                invoice.totalAmount().negate(), BigDecimal.ZERO, newBlueUnarchived,
                account.redConfirmedAmount(), now);
        return toResponse(invoiceRepository.findById(invoice.id()).orElseThrow(), false);
    }

    /**
     * 按发票号码查询，蓝票附带红冲链，红票附带原票号码。重复查询结果稳定。
     */
    @Transactional(readOnly = true)
    public InvoiceResponse getByInvoiceNo(String invoiceNo) {
        InvoiceRow invoice = invoiceRepository.findByInvoiceNo(invoiceNo)
                .orElseThrow(() -> ApiException.notFound("发票不存在：" + invoiceNo));
        return toResponse(invoice, false);
    }

    /**
     * 行项目校验并汇总金额：行金额须等于数量×单价，金额精度不超过分。
     */
    private BigDecimal validateAndSumLines(List<InvoiceLineRequest> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceLineRequest line : lines) {
            requireScale(line.amount(), "行金额");
            BigDecimal expected = line.quantity().multiply(line.unitPrice());
            if (expected.compareTo(line.amount()) != 0) {
                throw ApiException.businessRule("行金额须等于数量×单价：" + line.itemName());
            }
            total = total.add(line.amount());
        }
        return total.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * 金额精度校验：不得超过分位。
     */
    static void requireScale(BigDecimal amount, String field) {
        if (amount.scale() > 2) {
            throw ApiException.businessRule(field + "精度不得超过分位：" + amount);
        }
    }

    /**
     * 幂等指纹：覆盖 requestId 与全部业务字段。
     */
    private String issueFingerprint(IssueInvoiceRequest request) {
        String[] fields = new String[5 + request.lines().size() * 4];
        fields[0] = request.requestId();
        fields[1] = request.sellerTaxNo();
        fields[2] = request.buyerTaxNo();
        fields[3] = request.invoiceNo();
        fields[4] = String.valueOf(Boolean.TRUE.equals(request.buyerBooked()));
        int i = 5;
        for (InvoiceLineRequest line : request.lines()) {
            fields[i++] = line.itemName();
            fields[i++] = line.quantity().toPlainString();
            fields[i++] = line.unitPrice().toPlainString();
            fields[i++] = line.amount().toPlainString();
        }
        return FingerprintUtil.fingerprint(fields);
    }

    /**
     * 唯一键冲突后的幂等裁决：同键同参返回首次结果，否则 409。
     */
    private InvoiceResponse resolveDuplicate(String requestId, String fingerprint) {
        return invoiceRepository.findByRequestId(requestId)
                .map(row -> {
                    if (!row.fingerprint().equals(fingerprint)) {
                        throw ApiException.idempotencyConflict("相同 requestId 的业务字段不一致");
                    }
                    return toResponse(row, true);
                })
                .orElseThrow(() -> ApiException.idempotencyConflict("发票号码已存在"));
    }

    private InvoiceRow lockInvoice(String invoiceNo) {
        return invoiceRepository.lockByInvoiceNo(invoiceNo)
                .orElseThrow(() -> ApiException.notFound("发票不存在：" + invoiceNo));
    }

    /**
     * 组装发票响应：蓝票带红冲链，红票带原票号码。
     */
    InvoiceResponse toResponse(InvoiceRow invoice, boolean idempotentReplay) {
        List<InvoiceResponse.Line> lines = invoiceRepository.findLines(invoice.id()).stream()
                .map(line -> new InvoiceResponse.Line(line.lineNo(), line.itemName(), line.quantity(),
                        line.unitPrice(), line.amount(), line.redFlushedAmount()))
                .toList();
        String originalInvoiceNo = null;
        List<String> redChain = List.of();
        if (invoice.direction() == InvoiceDirection.RED && invoice.originalInvoiceId() != null) {
            originalInvoiceNo = invoiceRepository.findById(invoice.originalInvoiceId())
                    .map(InvoiceRow::invoiceNo).orElse(null);
        }
        if (invoice.direction() == InvoiceDirection.BLUE) {
            redChain = invoiceRepository.findRedInvoicesByOriginalId(invoice.id()).stream()
                    .map(InvoiceRow::invoiceNo)
                    .toList();
        }
        return new InvoiceResponse(invoice.invoiceNo(), invoice.direction().name(), invoice.status().name(),
                invoice.sellerTaxNo(), invoice.buyerTaxNo(), invoice.buyerBooked(), invoice.totalAmount(),
                invoice.redFlushedAmount(), invoice.redRemainAmount(), originalInvoiceNo, redChain, lines,
                idempotentReplay, invoice.createdAt());
    }

    /**
     * 生成业务单号：前缀＋20 位十六进制随机串。
     */
    static String generateNo(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
