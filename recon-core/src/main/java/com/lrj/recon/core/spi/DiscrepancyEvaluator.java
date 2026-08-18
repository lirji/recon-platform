package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;

import java.util.List;

/**
 * 插件 3) 判差: 纯函数, 内置 Exact/Tolerance, 预留 Drools。
 *
 * <p>MVP 实现: {@link com.lrj.recon.core.domain.service.ExactEvaluator} (一组只发一条主类型)。
 */
public interface DiscrepancyEvaluator {

    String evaluatorId();

    /** 对一个匹配组判差; 无差异返回空列表, 有差异返回单条主类型 (设计 §9)。 */
    List<Discrepancy> evaluate(MatchGroup group, DiscrepancyRule rule, EvaluationContext ctx);
}
