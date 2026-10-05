package com.example.starter.deicing.service;

import com.example.starter.deicing.db.ManualReviewPo;
import com.example.starter.deicing.db.ManualReviewRepository;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.ManualReviewView;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工确认队列：库存不足的两步法申请转入，不静默降级。
 * 记录使用独立事务，保证申请事务回滚后转人工记录仍保留。
 */
@Service
public class ManualReviewService {

    /** 转人工成因：库存不足。 */
    public static final String REASON_INSUFFICIENT_FLUID = "INSUFFICIENT_FLUID";

    private final ManualReviewRepository manualReviewRepository;
    private final TimeSource timeSource;

    public ManualReviewService(ManualReviewRepository manualReviewRepository,
                               TimeSource timeSource) {
        this.manualReviewRepository = manualReviewRepository;
        this.timeSource = timeSource;
    }

    /**
     * 记录库存不足转人工（独立事务）；同 requestId 重复转入幂等忽略。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInsufficientFluid(ApplicationRequest request, String detail) {
        ManualReviewPo po = new ManualReviewPo();
        po.setRequestId(request.requestId());
        po.setFlightNumber(request.flightNumber());
        po.setReason(REASON_INSUFFICIENT_FLUID);
        po.setDetail(detail);
        po.setStatus("PENDING");
        po.setCreatedAt(timeSource.now());
        try {
            manualReviewRepository.insert(po);
        } catch (DuplicateKeyException ignored) {
            // 同键重复转入：幂等忽略，保留首次记录
        }
    }

    @Transactional(readOnly = true)
    public List<ManualReviewView> list() {
        return manualReviewRepository.findAll().stream()
                .map(po -> new ManualReviewView(po.getRequestId(), po.getFlightNumber(),
                        po.getReason(), po.getDetail(), po.getStatus(), po.getCreatedAt()))
                .toList();
    }
}
