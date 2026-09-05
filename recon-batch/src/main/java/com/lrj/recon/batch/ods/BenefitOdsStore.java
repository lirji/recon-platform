package com.lrj.recon.batch.ods;

/** 持久化端口：ODS 编排层不依赖具体 JDBC 实现。 */
public interface BenefitOdsStore {
    /** 写入由 factType 指定的唯一 ODS 角色表。 */
    void insert(BenefitOdsEvent event);
}
