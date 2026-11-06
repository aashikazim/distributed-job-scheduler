package com.example.jobscheduler.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAccessFilter extends OncePerRequestFilter {
    public static final int MAX_REQUEST_BYTES = 128 * 1024;
    private final AccessProperties properties;
    private final ObjectMapper json;
    private final byte[] expected;

    public ApiAccessFilter(AccessProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        expected = digest(properties.apiKey() == null ? "" : properties.apiKey());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var rawPath = request.getRequestURI().substring(request.getContextPath().length());
        // Reject ambiguous path aliases before applying any public-route exemptions.
        if (rawPath.contains("%") || rawPath.contains(";") || rawPath.contains("\\")
                || rawPath.contains("//") || rawPath.contains("/../") || rawPath.contains("/./")
                || rawPath.endsWith("/..") || rawPath.endsWith("/.")) {
            problem(response, 400, "Invalid request path");
            return;
        }
        var servletPath = request.getServletPath();
        var path = servletPath.isEmpty() ? rawPath : servletPath;
        boolean actuator = path.equals("/actuator") || path.startsWith("/actuator/");
        if (properties.role() == AccessProperties.Role.WORKER && !actuator) {
            problem(response, 404, "Not found");
            return;
        }
        boolean docs = path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/")
                || path.equals("/v3/api-docs") || path.startsWith("/v3/api-docs/");
        if (!actuator && !docs) {
            var headers = request.getHeaders("X-API-Key");
            var supplied = headers.hasMoreElements() ? headers.nextElement() : "";
            boolean matches = MessageDigest.isEqual(expected, digest(supplied));
            if (!matches || supplied.isEmpty() || headers.hasMoreElements()) {
                problem(response, 401, "A valid X-API-Key header is required");
                return;
            }
            if (request.getMethod().equals("POST") || request.getMethod().equals("PUT")) {
                if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
                    problem(response, 413, "Request body exceeds 128 KB");
                    return;
                }
                var bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
                if (bytes.length > MAX_REQUEST_BYTES) {
                    problem(response, 413, "Request body exceeds 128 KB");
                    return;
                }
                chain.doFilter(new BufferedRequest(request, bytes), response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void problem(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatusCode.valueOf(status), detail));
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;

        BufferedRequest(HttpServletRequest request, byte[] bytes) {
            super(request);
            this.bytes = bytes;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
