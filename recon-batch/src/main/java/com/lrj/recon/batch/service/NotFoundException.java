package com.lrj.recon.batch.service;

/**
 * 在线服务查询目标不存在 (discrepancy / run 未找到)。由 REST 层映射为 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
