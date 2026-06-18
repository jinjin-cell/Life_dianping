package com.lifedp.Filter;

import com.lifedp.utils.UserHolder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.*;
import java.io.IOException;

@Component
@Order(1)
public class SecurityFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            //兜底清理：即使前面任何环节异常，也确保 ThreadLocal 被清空
            UserHolder.removeUser();
        }
    }
}