package com.example.starter.museum.domain.po;

import java.time.Instant;

/** 文物有效借展占用表行：存在即代表该文物有一份未结案合同。 */
public class ArtifactActiveLoanPo {
    public Long artifactId;
    public Long contractId;
    public Instant createdAt;
}
