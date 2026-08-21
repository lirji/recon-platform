package com.lrj.recon.batch.ods;

/** 持久化端口：ODS 编排层不依赖具体 JDBC 实现。 */
public interface BenefitOdsStore {
    void insert(BenefitOdsEvent event);
}
