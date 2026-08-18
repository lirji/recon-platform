package com.lrj.recon.batch.web;

import com.lrj.recon.batch.service.NotFoundException;
import com.lrj.recon.core.domain.model.ConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * REST 统一异常映射 (设计 §4 API 契约: 标准化错误响应):
 * <ul>
 *   <li>{@link NotFoundException} → 404 (差异 / Run 不存在);</li>
 *   <li>{@link ConflictException} → 409 (乐观锁版本冲突, 幂等/并发核销挡冲突);</li>
 *   <li>{@link IllegalStateException} → 409 (非法状态流转, 状态冲突);</li>
 *   <li>{@link IllegalArgumentException} → 400 (参数校验失败)。</li>
 * </ul>
 * 错误体统一 {@code {error, message}}。
 */
@RestControllerAdvice
public class ReconApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> illegalState(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "illegal_transition", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of("error", error, "message", message == null ? "" : message));
    }
}
