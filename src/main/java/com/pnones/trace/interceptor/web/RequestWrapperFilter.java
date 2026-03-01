package com.pnones.trace.interceptor.web;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Filter that wraps HttpServletRequest with ContentCachingRequestWrapper
 * BEFORE any application filters execute. This ensures request body can be
 * read multiple times without consuming the InputStream.
 */
public class RequestWrapperFilter implements Filter {
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        com.pnones.trace.util.DebugLogger.log("RequestWrapperFilter initialized");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        ServletRequest wrappedRequest = request;
        
        if (request instanceof HttpServletRequest) {
            try {
                Object wrapped = com.pnones.trace.agent.RequestBodyCapture.wrapRequestIfNeeded(request);
                if (wrapped != request && wrapped instanceof ServletRequest) {
                    wrappedRequest = (ServletRequest) wrapped;
                    com.pnones.trace.util.DebugLogger.log("RequestWrapperFilter: request wrapped successfully");
                }
            } catch (Throwable t) {
                com.pnones.trace.util.DebugLogger.log("RequestWrapperFilter: wrap failed", t);
            }
        }
        
        chain.doFilter(wrappedRequest, response);
    }
    
    @Override
    public void destroy() {
        com.pnones.trace.util.DebugLogger.log("RequestWrapperFilter destroyed");
    }
}
