package com.lrj.recon.core.domain.model;

/**
 * 判差器类型。MVP 仅实现 {@link #EXACT}; {@link #TOLERANCE} 归 M4; {@link #DROOLS} 阶段二,
 * 装配时遇到必须 fail-fast (绝不静默跳过判差)。
 */
public enum EvaluatorType {
    EXACT,
    TOLERANCE,
    DROOLS
}
