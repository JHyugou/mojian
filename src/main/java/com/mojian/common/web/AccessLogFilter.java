package com.mojian.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问日志过滤器（SRS 7.1）：只记录方法、路径、状态与耗时。
 * 刻意不记录任何请求头——Authorization 与模型 API Key 从源头不进日志。
 */
@Slf4j
@Order(1)
@Component
public class AccessLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("{} {} -> {} ({}ms)",
                    request.getMethod(), request.getRequestURI(),
                    response.getStatus(), System.currentTimeMillis() - start);
        }
    }
}
