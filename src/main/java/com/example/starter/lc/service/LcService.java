package com.example.starter.lc.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.lc.domain.Discrepancy;
import com.example.starter.lc.domain.IdempotentRequest;
import com.example.starter.lc.domain.LcAccount;
import com.example.starter.lc.domain.OperationType;
import com.example.starter.lc.domain.PresentationBatch;
import com.example.starter.lc.domain.PresentationStatus;
import com.example.starter.lc.repository.DiscrepancyRepository;
import com.example.starter.lc.repository.IdempotentRequestRepository;
import com.example.starter.lc.repository.LcRepository;
import com.example.starter.lc.repository.PresentationRepository;
import com.example.starter.lc.web.dto.CreateLcRequest;
import com.example.starter.lc.web.dto.DiscrepancyInput;
import com.example.starter.lc.web.dto.LcResponse;
import com.example.starter.lc.web.dto.NoticeRequest;
import com.example.starter.lc.web.dto.PayRequest;
import com.example.starter.lc.web.dto.PresentRequest;
import com.example.starter.lc.web.dto.PresentationResponse;
import com.example.starter.lc.web.dto.RefundRequest;
import com.example.starter.lc.web.dto.WaiverItem;
import com.example.starter.lc.web.dto.WaiverRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 信用证审单不符点与拒付决策核心服务。
 * <p>所有写操作以 requestId 幂等：指纹覆盖 requestId 与全部业务字段；
 * 业务变更与幂等首次响应在同一事务内原子提交。
 */
@Service
public class LcService {

    private static final Logger log = LoggerFactory.getLogger(LcService.class);

    private final LcRepository lcRepository;
    private final PresentationRepository presentationRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final IdempotentRequestRepository idempotentRepository;
    private final FingerprintService fingerprintService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public LcService(LcRepository lcRepository, PresentationRepository presentationRepository,
            DiscrepancyRepository discrepancyRepository,
            IdempotentRequestRepository idempotentRepository,
            FingerprintService fingerprintService, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.lcRepository = lcRepository;
        this.presentationRepository = presentationRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.idempotentRepository = idempotentRepository;
        this.fingerprintService = fingerprintService;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 开立合成信用证。 */
    public LcResponse createLc(CreateLcRequest req) {
        LocalDateTime now = now();
        LcAccount a = new LcAccount();
        a.setLcNo(req.lcNo());
        a.setApplicant(req.applicant());
        a.setBeneficiary(req.beneficiary());
        a.setCurrency(req.currency() == null || req.currency().isBlank() ? "USD" : req.currency());
        a.setAmountTotal(req.amountTotal());
        a.setAmountAvailable(req.amountTotal());
        a.setExpiryUtc(req.expiryUtc());
        a.setCreatedAtUtc(now);
        a.setUpdatedAtUtc(now);
        try {
            lcRepository.insert(a);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("LC_EXISTS", "信用证编号已存在: " + req.lcNo());
        }
        return new LcResponse(a.getLcNo(), a.getApplicant(), a.getBeneficiary(), a.getCurrency(),
                a.getAmountTotal(), a.getAmountAvailable(), a.getExpiryUtc());
    }

    /** 查询信用证。 */
    public LcResponse getLc(String lcNo) {
        LcAccount a = requireLc(lcNo);
        return new LcResponse(a.getLcNo(), a.getApplicant(), a.getBeneficiary(), a.getCurrency(),
                a.getAmountTotal(), a.getAmountAvailable(), a.getExpiryUtc());
    }

    /**
     * 受益人交单 / 拒付后改单重交。重交生成新批次并重新起算 5 个银行工作日审单期。
     */
    public PresentationResponse present(PresentRequest req) {
        String fp = fingerprintService.present(req.requestId(), req.lcNo(), req.presentationNo(),
                req.supersedesPresentationNo(), req.drawAmount().toPlainString(), req.docs());
        return executeIdempotent(OperationType.PRESENT, req.requestId(), fp,
                () -> doPresent(req));
    }

    private PresentationResponse doPresent(PresentRequest req) {
        LocalDateTime now = now();
        presentationRepository.autoDeemAccepted(now, now);

        LcAccount lc = requireLc(req.lcNo());
        if (lc.getExpiryUtc() != null && now.isAfter(lc.getExpiryUtc())) {
            throw BusinessException.unprocessable("LC_EXPIRED",
                    "信用证交单有效期已过，不得交单/改单重交");
        }

        Long supersedesId = null;
        int newSeq;
        if (req.supersedesPresentationNo() != null && !req.supersedesPresentationNo().isBlank()) {
            PresentationBatch oldBatch = requireBatch(req.supersedesPresentationNo());
            if (!oldBatch.getLcNo().equals(req.lcNo())) {
                throw BusinessException.unprocessable("LC_MISMATCH",
                        "被取代批次不属于该信用证");
            }
            if (oldBatch.getStatus() != PresentationStatus.REJECTED) {
                throw BusinessException.conflict("BATCH_NOT_REJECTED",
                        "仅拒付状态的批次可改单重交，当前状态: " + oldBatch.getStatus());
            }
            int rows = presentationRepository.markSuperseded(oldBatch.getId(), now);
            if (rows == 0) {
                throw BusinessException.conflict("BATCH_STATE_CHANGED",
                        "被取代批次状态已变化，改单重交失败");
            }
            supersedesId = oldBatch.getId();
            newSeq = oldBatch.getBatchSeq() + 1;
        } else {
            int maxSeq = presentationRepository.maxBatchSeq(req.lcNo());
            if (maxSeq > 0) {
                throw BusinessException.conflict("BATCH_EXISTS",
                        "该信用证已有交单批次；拒付后改单重交须提供 supersedesPresentationNo");
            }
            newSeq = 1;
        }

        PresentationBatch b = new PresentationBatch();
        b.setPresentationNo(req.presentationNo());
        b.setLcNo(req.lcNo());
        b.setBatchSeq(newSeq);
        b.setSupersedesId(supersedesId);
        b.setStatus(PresentationStatus.UNDER_REVIEW);
        b.setDrawAmount(req.drawAmount());
        b.setDocsJson(writeJson(req.docs()));
        b.setReceivedAtUtc(now);
        b.setDeadlineUtc(BankingCalendar.reviewDeadline(now));
        b.setActiveSlot(lc.getId());
        b.setCreatedAtUtc(now);
        b.setUpdatedAtUtc(now);
        try {
            presentationRepository.insert(b);
        } catch (DuplicateKeyException e) {
            // 同一信用证同一时刻只允许一个审单中批次（uq_active_presentation），或批次号重复。
            throw BusinessException.conflict("PRESENTATION_IN_PROGRESS",
                    "同一信用证已有审单中的交单批次，并发交单仅唯一受理；或交单批次号已存在");
        }
        return toResponse(b, false);
    }

    /**
     * 银行发出拒付通知。时效内一次性列明全部不符点并锁定单据状态；
     * 超过第5个银行工作日17:00发出的通知无效，批次自动转为“视为接受”。
     */
    public PresentationResponse notice(NoticeRequest req) {
        String fp = fingerprintService.notice(req.requestId(), req.presentationNo(),
                req.discrepancies());
        return executeIdempotent(OperationType.NOTICE, req.requestId(), fp,
                () -> doNotice(req));
    }

    private PresentationResponse doNotice(NoticeRequest req) {
        LocalDateTime now = now();
        presentationRepository.autoDeemAccepted(now, now);

        PresentationBatch batch = requireBatch(req.presentationNo());
        if (batch.getStatus() == PresentationStatus.DEEMED_ACCEPTED) {
            // 已超期：通知无效，批次已自动转为视为接受，返回该状态而非受理通知。
            batch.setDiscrepancies(discrepancyRepository.findByPresentationId(batch.getId()));
            return toResponse(batch, false);
        }
        if (batch.getStatus() != PresentationStatus.UNDER_REVIEW) {
            throw BusinessException.conflict("BATCH_NOT_UNDER_REVIEW",
                    "批次当前状态为 " + batch.getStatus() + "，不得发出拒付通知；不符点不得事后补充");
        }

        if (!BankingCalendar.isNoticeInTime(now, batch.getDeadlineUtc())) {
            // 通知无效：超期发出，自动转为视为接受。
            int rows = presentationRepository.markAccepted(batch.getPresentationNo(),
                    PresentationStatus.DEEMED_ACCEPTED, List.of(PresentationStatus.UNDER_REVIEW),
                    now, now);
            if (rows == 0) {
                throw BusinessException.conflict("BATCH_STATE_CHANGED", "批次状态已被并发操作改变");
            }
            PresentationBatch updated = requireBatch(req.presentationNo());
            return toResponse(updated, false);
        }

        List<Discrepancy> discrepancies = normalizeAndMap(req.discrepancies());
        int rows = presentationRepository.markRejected(batch.getPresentationNo(), now, now);
        if (rows == 0) {
            throw BusinessException.conflict("BATCH_STATE_CHANGED",
                    "批次状态已被并发操作改变，拒付通知未被受理");
        }
        discrepancyRepository.insertAll(batch.getId(), discrepancies);
        PresentationBatch rejected = requireBatch(req.presentationNo());
        rejected.setDiscrepancies(discrepancyRepository.findByPresentationId(batch.getId()));
        return toResponse(rejected, false);
    }

    /**
     * 开证申请人放弃不符点。须覆盖拒付通知中全部已列不符点；部分放弃无效（状态不变）。
     */
    public PresentationResponse waive(WaiverRequest req) {
        String fp = fingerprintService.waiver(req.requestId(), req.presentationNo(),
                req.waivedDiscrepancies());
        return executeIdempotent(OperationType.WAIVER, req.requestId(), fp,
                () -> doWaive(req));
    }

    private PresentationResponse doWaive(WaiverRequest req) {
        LocalDateTime now = now();
        presentationRepository.autoDeemAccepted(now, now);

        PresentationBatch batch = requireBatch(req.presentationNo());
        if (batch.getStatus() != PresentationStatus.REJECTED) {
            throw BusinessException.conflict("BATCH_NOT_REJECTED",
                    "仅拒付状态的批次可由申请人放弃不符点，当前状态: " + batch.getStatus());
        }

        List<Discrepancy> listed = discrepancyRepository.findByPresentationId(batch.getId());
        Set<String> listedKeys = new HashSet<>();
        for (Discrepancy d : listed) {
            listedKeys.add(d.getDocType() + "" + d.getDiscrepancyCode());
        }
        Set<String> waivedKeys = new HashSet<>();
        for (WaiverItem item : req.waivedDiscrepancies()) {
            waivedKeys.add(item.docType() + "" + item.code());
        }
        // 部分放弃（少覆盖）或声明了未列明不符点（多覆盖）均视为无效，状态不变。
        if (!waivedKeys.equals(listedKeys)) {
            Set<String> missing = new HashSet<>(listedKeys);
            missing.removeAll(waivedKeys);
            Set<String> extra = new HashSet<>(waivedKeys);
            extra.removeAll(listedKeys);
            throw BusinessException.unprocessable("PARTIAL_WAIVER",
                    "放弃声明须覆盖全部已列不符点，部分放弃无效；缺少: " + missing + "，多余: " + extra);
        }

        int rows = presentationRepository.markAccepted(batch.getPresentationNo(),
                PresentationStatus.ACCEPTED, List.of(PresentationStatus.REJECTED), now, now);
        if (rows == 0) {
            // 与拒付退单并发：唯一终态，本侧放弃失败。
            throw BusinessException.conflict("TERMINAL_STATE_RACE",
                    "批次已形成其他终态（可能已拒付退单），放弃未生效");
        }
        PresentationBatch accepted = requireBatch(req.presentationNo());
        accepted.setDiscrepancies(listed);
        return toResponse(accepted, false);
    }

    /**
     * 银行付款承兑（终态）。额度恰足可付；不足则整笔拒绝且状态不变；
     * 重复付款指令返回首次结果，不重复核减额度。
     */
    public PresentationResponse pay(PayRequest req) {
        String fp = fingerprintService.simple("PAY", req.requestId(), req.presentationNo());
        return executeIdempotent(OperationType.PAY, req.requestId(), fp,
                () -> doPay(req));
    }

    private PresentationResponse doPay(PayRequest req) {
        LocalDateTime now = now();
        presentationRepository.autoDeemAccepted(now, now);

        PresentationBatch batch = requireBatch(req.presentationNo());
        PresentationStatus st = batch.getStatus();
        if (st == PresentationStatus.PAID) {
            // 付款为终态：重复付款指令（即使使用新 requestId）返回首次结果，不重复扣减额度。
            batch.setDiscrepancies(discrepancyRepository.findByPresentationId(batch.getId()));
            return toResponse(batch, false);
        }
        if (st != PresentationStatus.ACCEPTED && st != PresentationStatus.DEEMED_ACCEPTED) {
            throw BusinessException.conflict("BATCH_NOT_ACCEPTED",
                    "仅已接受（含视为接受）批次可付款，当前状态: " + st);
        }

        int deducted = lcRepository.deductAvailable(batch.getLcNo(), batch.getDrawAmount());
        if (deducted == 0) {
            throw BusinessException.unprocessable("INSUFFICIENT_AVAILABLE_AMOUNT",
                    "信用证可用额度不足，付款整笔拒绝，额度未核减");
        }
        int paid = presentationRepository.markPaid(batch.getPresentationNo(), now, now);
        if (paid == 0) {
            // 并发状态变化：回滚事务，额度随之恢复。
            throw BusinessException.conflict("BATCH_STATE_CHANGED",
                    "批次状态已被并发操作改变，付款未生效，额度已回退");
        }
        PresentationBatch result = requireBatch(req.presentationNo());
        result.setDiscrepancies(discrepancyRepository.findByPresentationId(batch.getId()));
        return toResponse(result, false);
    }

    /** 拒付后申请人未有效放弃，银行拒付退单结案（终态）。与放弃并发时形成唯一终态。 */
    public PresentationResponse refund(RefundRequest req) {
        String fp = fingerprintService.simple("REFUND", req.requestId(), req.presentationNo());
        return executeIdempotent(OperationType.REFUND, req.requestId(), fp,
                () -> doRefund(req));
    }

    private PresentationResponse doRefund(RefundRequest req) {
        LocalDateTime now = now();
        presentationRepository.autoDeemAccepted(now, now);

        PresentationBatch batch = requireBatch(req.presentationNo());
        if (batch.getStatus() != PresentationStatus.REJECTED) {
            throw BusinessException.conflict("BATCH_NOT_REJECTED",
                    "仅拒付状态的批次可退单，当前状态: " + batch.getStatus());
        }
        int rows = presentationRepository.markRefunded(batch.getPresentationNo(), now, now);
        if (rows == 0) {
            throw BusinessException.conflict("TERMINAL_STATE_RACE",
                    "批次已形成其他终态（可能申请人已放弃不符点），退单未生效");
        }
        PresentationBatch result = requireBatch(req.presentationNo());
        result.setDiscrepancies(discrepancyRepository.findByPresentationId(batch.getId()));
        return toResponse(result, false);
    }

    /** 查询批次当前状态（查询时顺带把超期未决批次转为视为接受）。 */
    public PresentationResponse getPresentation(String presentationNo) {
        return txTemplate.execute(status -> {
            LocalDateTime now = now();
            presentationRepository.autoDeemAccepted(now, now);
            PresentationBatch batch = requireBatch(presentationNo);
            batch.setDiscrepancies(discrepancyRepository.findByPresentationId(batch.getId()));
            return toResponse(batch, false);
        });
    }

    // ------------------------------------------------------------------
    // 幂等执行器
    // ------------------------------------------------------------------

    /**
     * 幂等执行：同键同参返回首次结果；同键异参返回 409 且不改变单据状态。
     * 业务动作与幂等首次响应记录在同一事务提交；并发同键唯一受理，落败方重查后
     * 返回首次结果或 409。
     */
    private PresentationResponse executeIdempotent(OperationType op, String requestId,
            String fingerprint, java.util.function.Supplier<PresentationResponse> action) {
        SavedReply saved = findSaved(requestId, fingerprint);
        if (saved != null) {
            return deserialize(saved.body(), true);
        }
        try {
            return txTemplate.execute(status -> {
                // 事务内再查一次，封堵并发窗口。
                SavedReply inside = findSaved(requestId, fingerprint);
                if (inside != null) {
                    return deserialize(inside.body(), true);
                }
                PresentationResponse resp = action.get();
                saveReply(requestId, op, fingerprint, 200, resp);
                return resp;
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：对方事务可能刚提交，等待其落盘后按重放/409处理。
            SavedReply winner = awaitSaved(requestId, fingerprint);
            if (winner != null) {
                return deserialize(winner.body(), true);
            }
            throw e;
        } catch (RuntimeException ex) {
            // 并发同键同参（如同时付款）：本事务可能因对方先提交而失败回滚，
            // 若首次结果已落盘则按重放返回，避免重复副作用。
            SavedReply winner = findSaved(requestId, fingerprint);
            if (winner != null) {
                return deserialize(winner.body(), true);
            }
            throw ex;
        }
    }

    private SavedReply findSaved(String requestId, String fingerprint) {
        IdempotentRequest rec = idempotentRepository.findByRequestId(requestId);
        if (rec == null) {
            return null;
        }
        if (!rec.getFingerprint().equals(fingerprint)) {
            throw BusinessException.conflict("IDEMPOTENCY_KEY_CONFLICT",
                    "requestId 已用于不同业务参数的请求，返回 409 且不改变单据状态");
        }
        return new SavedReply(rec.getHttpStatus(), rec.getResponseBody());
    }

    private SavedReply awaitSaved(String requestId, String fingerprint) {
        for (int i = 0; i < 40; i++) {
            SavedReply reply = findSaved(requestId, fingerprint);
            if (reply != null) {
                return reply;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private void saveReply(String requestId, OperationType op, String fingerprint, int httpStatus,
            PresentationResponse resp) {
        IdempotentRequest rec = new IdempotentRequest();
        rec.setRequestId(requestId);
        rec.setOperationType(op);
        rec.setFingerprint(fingerprint);
        rec.setHttpStatus(httpStatus);
        rec.setResponseBody(writeJson(resp));
        rec.setCreatedAtUtc(now());
        idempotentRepository.insert(rec);
    }

    private PresentationResponse deserialize(String json, boolean replayed) {
        try {
            PresentationResponse resp = objectMapper.readValue(json, PresentationResponse.class);
            return new PresentationResponse(resp.presentationNo(), resp.lcNo(), resp.batchSeq(),
                    resp.status(), resp.drawAmount(), resp.receivedAtUtc(), resp.deadlineUtc(),
                    resp.noticeAtUtc(), resp.acceptedAtUtc(), resp.paidAtUtc(), resp.refundedAtUtc(),
                    resp.discrepancies(), replayed);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法反序列化幂等首次响应", e);
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private LcAccount requireLc(String lcNo) {
        LcAccount a = lcRepository.findByLcNo(lcNo);
        if (a == null) {
            throw BusinessException.notFound("信用证不存在: " + lcNo);
        }
        return a;
    }

    private PresentationBatch requireBatch(String presentationNo) {
        PresentationBatch b = presentationRepository.findByNo(presentationNo);
        if (b == null) {
            throw BusinessException.notFound("交单批次不存在: " + presentationNo);
        }
        return b;
    }

    /** 不符点排序规范化（先按单据类型，再按代码、描述），并检测重复条目。 */
    private List<Discrepancy> normalizeAndMap(List<DiscrepancyInput> inputs) {
        List<DiscrepancyInput> sorted = new ArrayList<>(inputs);
        sorted.sort((a, b) -> {
            int c = a.docType().compareTo(b.docType());
            if (c != 0) {
                return c;
            }
            c = a.code().compareTo(b.code());
            return c != 0 ? c : String.valueOf(a.description()).compareTo(String.valueOf(b.description()));
        });
        Set<String> seen = new HashSet<>();
        List<Discrepancy> result = new ArrayList<>();
        int seq = 1;
        for (DiscrepancyInput in : sorted) {
            String key = in.docType() + "" + in.code();
            if (!seen.add(key)) {
                throw BusinessException.unprocessable("DUPLICATE_DISCREPANCY",
                        "不符点重复: " + in.docType() + "/" + in.code());
            }
            result.add(new Discrepancy(in.docType(), in.code(),
                    in.description() == null ? "" : in.description(), seq++));
        }
        return result;
    }

    private PresentationResponse toResponse(PresentationBatch b, boolean replayed) {
        List<PresentationResponse.DiscrepancyView> views = b.getDiscrepancies().stream()
                .map(d -> new PresentationResponse.DiscrepancyView(
                        d.getDocType(), d.getDiscrepancyCode(), d.getDescription(), d.getSeqNo()))
                .toList();
        return new PresentationResponse(b.getPresentationNo(), b.getLcNo(), b.getBatchSeq(),
                b.getStatus().name(), b.getDrawAmount(), b.getReceivedAtUtc(), b.getDeadlineUtc(),
                b.getNoticeAtUtc(), b.getAcceptedAtUtc(), b.getPaidAtUtc(), b.getRefundedAtUtc(),
                views, replayed);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private record SavedReply(int httpStatus, String body) {
    }
}
