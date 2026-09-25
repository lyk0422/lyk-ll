package com.example.starter.pv.service;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.example.starter.pv.web.BatchResponse;
import com.example.starter.pv.web.CaseView;
import com.example.starter.pv.web.CloseRequest;
import com.example.starter.pv.web.CloseResponse;
import com.example.starter.pv.web.FollowUpRequest;
import com.example.starter.pv.web.FollowUpResponse;
import com.example.starter.pv.web.InitialReportRequest;
import com.example.starter.pv.web.InitialReportResponse;
import com.example.starter.pv.web.SubmitBatchRequest;
import org.springframework.stereotype.Service;

/**
 * 药物警戒个例服务门面：初报按“患者|药品|事件”去重键进程内串行化，
 * 保证同一去重键上“新建/并入”判定在本实例内无竞态；锁在事务提交后释放，
 * 后来的重复初报一定能读到已提交的活动个例。跨实例场景另由数据库唯一约束兜底。
 */
@Service
public class CaseService {

    private final CaseTransactionOps txOps;
    private final ConcurrentHashMap<String, ReentrantLock> dedupLocks = new ConcurrentHashMap<>();

    public CaseService(CaseTransactionOps txOps) {
        this.txOps = txOps;
    }

    /**
     * 初报提交（按去重键串行）。
     */
    public InitialReportResponse submitInitial(InitialReportRequest request) {
        String key = request.patientCode() + "|" + request.drugCode() + "|"
                + request.reactionEvent();
        ReentrantLock lock = dedupLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return txOps.submitInitialTx(request);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 随访信息提交。
     */
    public FollowUpResponse submitFollowUp(String caseNo, FollowUpRequest request) {
        return txOps.submitFollowUpTx(caseNo, request);
    }

    /**
     * 申请关闭。
     */
    public CloseResponse close(String caseNo, CloseRequest request) {
        return txOps.closeTx(caseNo, request);
    }

    /**
     * 提交监管批次。
     */
    public BatchResponse submitBatch(SubmitBatchRequest request) {
        return txOps.submitBatchTx(request);
    }

    /**
     * 查询个例详情。
     */
    public CaseView getCase(String caseNo) {
        return txOps.getCase(caseNo);
    }

    /**
     * 查询全部个例。
     */
    public List<CaseView> listCases() {
        return txOps.listCases();
    }

    /**
     * 查询批次快照。
     */
    public BatchResponse getBatch(String batchNo) {
        return txOps.getBatch(batchNo);
    }

    /**
     * 暴露 UTC 时钟（测试/复算使用）。
     */
    public Clock clock() {
        return txOps.clock();
    }
}
