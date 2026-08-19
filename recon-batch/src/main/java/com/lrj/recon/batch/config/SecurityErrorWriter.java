package com.lrj.recon.batch.config;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 统一鉴权错误 JSON 输出 (401/403),对齐 risk-platform {@code SecurityErrorWriter}。 */
final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().printf("{\"error\":\"%s\",\"message\":\"%s\"}", code, message);
    }
}
