package com.example.rebate.service;

import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.Doubt;
import com.example.rebate.web.dto.BatchView;
import com.example.rebate.web.dto.CheckResponse;
import java.util.List;

/**
 * 批次/校验视图组装。
 */
final class BatchViews {

    private BatchViews() {
    }

    static BatchView batch(DeclarationBatch b) {
        return new BatchView(b.batchNo(), b.status().name(), b.declareDate().toString(),
                b.totalDeclaredTax(), b.frozenAt(), b.voidedAt(), b.closedAt());
    }

    static CheckResponse check(DeclarationBatch b, List<CheckDetail> details, List<Doubt> doubts) {
        List<CheckResponse.DetailView> detailViews = details.stream()
                .map(d -> new CheckResponse.DetailView(
                        d.id(), d.declarationNo(), d.invoiceNo(), d.productCode(),
                        d.versionNo(), d.rebateRate(), d.taxBasis(), d.exchangeCost(),
                        d.result().name(), d.doubtType()))
                .toList();
        List<CheckResponse.DoubtView> doubtViews = doubts.stream()
                .map(d -> new CheckResponse.DoubtView(d.id(), d.checkDetailId(),
                        d.doubtType(), d.status().name(), d.explanation()))
                .toList();
        return new CheckResponse(b.batchNo(), b.status().name(), b.declareDate().toString(),
                b.totalDeclaredTax(), detailViews, doubtViews);
    }
}
