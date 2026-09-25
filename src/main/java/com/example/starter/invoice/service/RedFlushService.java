package com.example.starter.invoice.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.invoice.domain.InfoStatus;
import com.example.starter.invoice.domain.InvoiceDirection;
import com.example.starter.invoice.domain.InvoiceStatus;
import com.example.starter.invoice.domain.Opinion;
import com.example.starter.invoice.domain.Party;
import com.example.starter.invoice.domain.RedType;
import com.example.starter.invoice.repo.CreditAccountRepository;
import com.example.starter.invoice.repo.CreditAccountRow;
import com.example.starter.invoice.repo.CreditLedgerRepository;
import com.example.starter.invoice.repo.InvoiceLineRow;
import com.example.starter.invoice.repo.InvoiceRepository;
import com.example.starter.invoice.repo.InvoiceRow;
import com.example.starter.invoice.repo.RedConfirmationRow;
import com.example.starter.invoice.repo.RedInfoLineRow;
import com.example.starter.invoice.repo.RedInfoRepository;
import com.example.starter.invoice.repo.RedInfoRow;
import com.example.starter.invoice.web.ApiException;
import com.example.starter.invoice.web.ConfirmRequest;
import com.example.starter.invoice.web.CreateRedInfoRequest;
import com.example.starter.invoice.web.RedInfoLineRequest;
import com.example.starter.invoice.web.RedInfoResponse;

/**
 * 红冲服务：红字信息表创建（幂等＋累计上限校验）、购销双方同事务确认、红票开具与额度恢复。
 */
@Service
public class RedFlushService {

    private final RedInfoRepository redInfoRepository;
    private final InvoiceRepository invoiceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final Clock clock;

    public RedFlushService(RedInfoRepository redInfoRepository,
                           InvoiceRepository invoiceRepository,
                           CreditAccountRepository creditAccountRepository,
                           CreditLedgerRepository creditLedgerRepository,
                           Clock clock) {
        this.redInfoRepository = redInfoRepository;
        this.invoiceRepository = invoiceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.creditLedgerRepository = creditLedgerRepository;
        this.clock = clock;
    }

    /**
     * 创建红字信息表。以 requestId 幂等；已全额红冲的蓝票为终态，返回最近一次信息表的幂等结果，
     * 不产生新信息表与红票。
     */
    @Transactional
    public RedInfoResponse createInfoTable(CreateRedInfoRequest request) {
        String fingerprint = infoFingerprint(request);
        var existing = redInfoRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            RedInfoRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.idempotencyConflict("相同 requestId 的业务字段不一致");
            }
            return toResponse(row, true);
        }

        InvoiceRow invoice = invoiceRepository.lockByInvoiceNo(request.originalInvoiceNo())
                .orElseThrow(() -> ApiException.notFound("原蓝票不存在：" + request.originalInvoiceNo()));
        if (invoice.direction() != InvoiceDirection.BLUE) {
            throw ApiException.businessRule("红字发票不允许再红冲");
        }
        if (invoice.status() == InvoiceStatus.RED_FLUSHED) {
            // 终态幂等：返回最近一次信息表结果，不产生新红票
            RedInfoRow latest = redInfoRepository.findLatestByOriginalInvoiceId(invoice.id())
                    .orElseThrow(() -> ApiException.businessRule("发票已全额红冲，为终态"));
            return toResponse(latest, true);
        }
        if (invoice.status() != InvoiceStatus.DELIVERED && invoice.status() != InvoiceStatus.ARCHIVED) {
            throw ApiException.businessRule("当前状态不允许红冲：" + invoice.status());
        }

        RedType redType = RedType.valueOf(request.redType());
        List<InvoiceLineRow> originalLines = invoiceRepository.findLines(invoice.id());
        BigDecimal totalAmount;
        List<RedInfoLineRequest> requestLines = request.lines() == null ? List.of() : request.lines();
        if (redType == RedType.FULL) {
            if (!requestLines.isEmpty()) {
                throw ApiException.businessRule("全额红冲不允许指定行项目");
            }
            totalAmount = invoice.redRemainAmount();
            if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.businessRule("可红冲余额为零");
            }
        } else {
            totalAmount = validatePartialLines(requestLines, originalLines, invoice);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String infoNo = InvoiceService.generateNo("RIF");
        long infoId = redInfoRepository.insert(request.requestId(), fingerprint, infoNo, invoice.id(),
                redType, invoice.sellerTaxNo(), invoice.buyerTaxNo(), totalAmount, now);
        if (redType == RedType.PARTIAL) {
            Map<Integer, InvoiceLineRow> lineByNo = originalLines.stream()
                    .collect(Collectors.toMap(InvoiceLineRow::lineNo, Function.identity()));
            for (RedInfoLineRequest line : requestLines) {
                redInfoRepository.insertLine(infoId, lineByNo.get(line.originalLineNo()).id(), line.amount());
            }
        }
        return toResponse(redInfoRepository.findByInfoNo(infoNo).orElseThrow(), false);
    }

    /**
     * 确认红字信息表：购销双方在同一事务内先后确认方可开具红票；单方确认不生效；
     * 意见不一致挂起；双方确认通过后在同一事务内开具红票并恢复额度。
     */
    @Transactional
    public RedInfoResponse confirm(String infoNo, ConfirmRequest request) {
        RedInfoRow info = redInfoRepository.lockByInfoNo(infoNo)
                .orElseThrow(() -> ApiException.notFound("红字信息表不存在：" + infoNo));
        if (info.status() == InfoStatus.CONFIRMED) {
            // 已开具红票，重复确认返回幂等结果
            return toResponse(info, true);
        }
        if (info.status() == InfoStatus.SUSPENDED) {
            throw ApiException.businessRule("信息表已挂起，需重新发起");
        }

        Party party = Party.valueOf(request.party());
        Opinion opinion = Opinion.valueOf(request.opinion());
        boolean alreadyConfirmed = party == Party.SELLER ? info.sellerConfirmed() : info.buyerConfirmed();
        if (alreadyConfirmed) {
            Opinion previous = lastOpinionOf(info.id(), party);
            if (previous == opinion) {
                return toResponse(info, true);
            }
            throw ApiException.businessRule("该方已确认，意见不可更改");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        redInfoRepository.insertConfirmation(info.id(), party, opinion, request.comment(), now);
        if (opinion == Opinion.REJECT) {
            redInfoRepository.updateStatus(info.id(), InfoStatus.SUSPENDED, null, now);
            return toResponse(redInfoRepository.findByInfoNo(infoNo).orElseThrow(), false);
        }

        redInfoRepository.updatePartyConfirmed(info.id(), party, now);
        boolean sellerConfirmed = info.sellerConfirmed() || party == Party.SELLER;
        boolean buyerConfirmed = info.buyerConfirmed() || party == Party.BUYER;
        if (!sellerConfirmed || !buyerConfirmed) {
            // 单方确认不生效
            return toResponse(redInfoRepository.findByInfoNo(infoNo).orElseThrow(), false);
        }

        // 双方均已确认：同一事务内开具红票并恢复额度
        long redInvoiceId = issueRedInvoice(info, now);
        redInfoRepository.updateStatus(info.id(), InfoStatus.CONFIRMED, redInvoiceId, now);
        return toResponse(redInfoRepository.findByInfoNo(infoNo).orElseThrow(), false);
    }

    /**
     * 按编号查询信息表，含确认轨迹。重复查询结果稳定。
     */
    @Transactional(readOnly = true)
    public RedInfoResponse getByInfoNo(String infoNo) {
        RedInfoRow info = redInfoRepository.findByInfoNo(infoNo)
                .orElseThrow(() -> ApiException.notFound("红字信息表不存在：" + infoNo));
        return toResponse(info, false);
    }

    /**
     * 双方确认后开具红票并恢复额度：全部写入在同一事务内，任一步失败整体回滚，
     * 不会出现红票生效而额度未恢复。
     */
    private long issueRedInvoice(RedInfoRow info, LocalDateTime now) {
        InvoiceRow original = invoiceRepository.lockById(info.originalInvoiceId())
                .orElseThrow(() -> ApiException.notFound("原蓝票不存在"));
        if (original.status() == InvoiceStatus.RED_FLUSHED) {
            throw ApiException.businessRule("原蓝票已全额红冲，为终态");
        }
        // 全额红冲须购方未入账或已取得购方确认（双方确认流程中购方确认已满足）
        if (info.redType() == RedType.FULL && original.buyerBooked() && !info.buyerConfirmed()
                && !hasBuyerConfirm(info.id())) {
            throw ApiException.businessRule("购方已入账，全额红冲须取得购方确认");
        }
        // 累计红冲金额不得超过可红冲余额；恰红至余额为零合法
        BigDecimal newFlushed = original.redFlushedAmount().add(info.totalAmount());
        if (newFlushed.compareTo(original.totalAmount()) > 0) {
            throw ApiException.businessRule("累计红冲金额超过可红冲余额");
        }

        // 开具红票，引用原发票号码，保留红冲链
        String redInvoiceNo = InvoiceService.generateNo("R");
        long redInvoiceId = invoiceRepository.insert("RED-" + info.infoNo(),
                FingerprintUtil.fingerprint("RED", info.infoNo()), redInvoiceNo, InvoiceDirection.RED,
                InvoiceStatus.DELIVERED, info.sellerTaxNo(), info.buyerTaxNo(), original.buyerBooked(),
                info.totalAmount(), original.id(), info.id(), now);

        List<InvoiceLineRow> originalLines = invoiceRepository.findLines(original.id());
        if (info.redType() == RedType.FULL) {
            int lineNo = 1;
            for (InvoiceLineRow line : originalLines) {
                BigDecimal remain = line.redRemainAmount();
                invoiceRepository.insertLine(redInvoiceId, lineNo++, line.itemName(),
                        BigDecimal.ONE, remain, remain);
                invoiceRepository.updateLineRedFlushed(line.id(), line.amount());
            }
        } else {
            Map<Long, InvoiceLineRow> lineById = originalLines.stream()
                    .collect(Collectors.toMap(InvoiceLineRow::id, Function.identity()));
            int lineNo = 1;
            for (RedInfoLineRow infoLine : redInfoRepository.findLines(info.id())) {
                InvoiceLineRow originalLine = lineById.get(infoLine.originalLineId());
                BigDecimal lineNewFlushed = originalLine.redFlushedAmount().add(infoLine.amount());
                if (lineNewFlushed.compareTo(originalLine.amount()) > 0) {
                    throw ApiException.businessRule("行项目累计红冲金额超过该行可红冲余额");
                }
                invoiceRepository.insertLine(redInvoiceId, lineNo++, originalLine.itemName(),
                        BigDecimal.ONE, infoLine.amount(), infoLine.amount());
                invoiceRepository.updateLineRedFlushed(originalLine.id(), lineNewFlushed);
            }
        }

        InvoiceStatus newStatus = newFlushed.compareTo(original.totalAmount()) == 0
                ? InvoiceStatus.RED_FLUSHED : original.status();
        invoiceRepository.updateStatusAndRedFlushed(original.id(), newStatus, newFlushed, now);

        // 额度在同一事务内恢复：已确认红票金额增加
        CreditAccountRow account = creditAccountRepository.lockBySellerTaxNo(info.sellerTaxNo())
                .orElseThrow(() -> ApiException.businessRule("销方无授信账户：" + info.sellerTaxNo()));
        BigDecimal newRedConfirmed = account.redConfirmedAmount().add(info.totalAmount());
        creditAccountRepository.updateAmounts(account.id(), account.blueUnarchivedAmount(),
                newRedConfirmed, now);
        creditLedgerRepository.append(account.sellerTaxNo(), "RED_CONFIRM", redInvoiceNo,
                BigDecimal.ZERO, info.totalAmount(), account.blueUnarchivedAmount(), newRedConfirmed, now);
        return redInvoiceId;
    }

    /**
     * 部分红冲行校验：仅允许按行项目拆分，每行不超过该行可红冲余额，合计不超过发票可红冲余额。
     */
    private BigDecimal validatePartialLines(List<RedInfoLineRequest> requestLines,
                                            List<InvoiceLineRow> originalLines, InvoiceRow invoice) {
        if (requestLines.isEmpty()) {
            throw ApiException.businessRule("部分红冲必须按行项目拆分");
        }
        Map<Integer, InvoiceLineRow> lineByNo = originalLines.stream()
                .collect(Collectors.toMap(InvoiceLineRow::lineNo, Function.identity()));
        BigDecimal total = BigDecimal.ZERO;
        for (RedInfoLineRequest line : requestLines) {
            InvoiceService.requireScale(line.amount(), "红冲金额");
            InvoiceLineRow originalLine = lineByNo.get(line.originalLineNo());
            if (originalLine == null) {
                throw ApiException.businessRule("原票行号不存在：" + line.originalLineNo());
            }
            if (line.amount().compareTo(originalLine.redRemainAmount()) > 0) {
                throw ApiException.businessRule("行 " + line.originalLineNo() + " 红冲金额超过可红冲余额："
                        + originalLine.redRemainAmount());
            }
            total = total.add(line.amount());
        }
        if (total.compareTo(invoice.redRemainAmount()) > 0) {
            throw ApiException.businessRule("累计红冲金额超过可红冲余额：" + invoice.redRemainAmount());
        }
        return total;
    }

    private Opinion lastOpinionOf(long infoTableId, Party party) {
        return redInfoRepository.findConfirmations(infoTableId).stream()
                .filter(row -> row.party() == party)
                .reduce((first, second) -> second)
                .map(RedConfirmationRow::opinion)
                .orElse(null);
    }

    private boolean hasBuyerConfirm(long infoTableId) {
        return redInfoRepository.findConfirmations(infoTableId).stream()
                .anyMatch(row -> row.party() == Party.BUYER && row.opinion() == Opinion.CONFIRM);
    }

    /**
     * 幂等指纹：覆盖 requestId 与全部业务字段。
     */
    private String infoFingerprint(CreateRedInfoRequest request) {
        List<RedInfoLineRequest> lines = request.lines() == null ? List.of() : request.lines();
        String[] fields = new String[3 + lines.size() * 2];
        fields[0] = request.requestId();
        fields[1] = request.originalInvoiceNo();
        fields[2] = request.redType();
        int i = 3;
        for (RedInfoLineRequest line : lines) {
            fields[i++] = String.valueOf(line.originalLineNo());
            fields[i++] = line.amount().toPlainString();
        }
        return FingerprintUtil.fingerprint(fields);
    }

    private RedInfoResponse toResponse(RedInfoRow info, boolean idempotentReplay) {
        String originalInvoiceNo = invoiceRepository.findById(info.originalInvoiceId())
                .map(InvoiceRow::invoiceNo).orElse(null);
        String redInvoiceNo = info.redInvoiceId() == null ? null
                : invoiceRepository.findById(info.redInvoiceId()).map(InvoiceRow::invoiceNo).orElse(null);
        Map<Long, Integer> lineNoById = invoiceRepository.findLines(info.originalInvoiceId()).stream()
                .collect(Collectors.toMap(InvoiceLineRow::id, InvoiceLineRow::lineNo));
        List<RedInfoResponse.Line> lines = redInfoRepository.findLines(info.id()).stream()
                .map(line -> new RedInfoResponse.Line(
                        lineNoById.getOrDefault(line.originalLineId(), 0), line.amount()))
                .toList();
        List<RedInfoResponse.Confirmation> confirmations = redInfoRepository.findConfirmations(info.id())
                .stream()
                .map(row -> new RedInfoResponse.Confirmation(row.party().name(), row.opinion().name(),
                        row.comment(), row.confirmedAt()))
                .toList();
        return new RedInfoResponse(info.infoNo(), info.status().name(), originalInvoiceNo,
                info.redType().name(), info.totalAmount(), info.sellerConfirmed(), info.buyerConfirmed(),
                redInvoiceNo, lines, confirmations, idempotentReplay, info.createdAt());
    }
}
