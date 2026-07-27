package io.github.essandhu.ledger.config;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401/403 as RFC 9457 problem documents. The API contract declares ALL errors problem+json; Spring
 * Security's defaults emit empty bodies with only the RFC 6750 {@code WWW-Authenticate} header.
 * Both conventions are kept: the bearer delegates set status + header, then a constant problem
 * body is written. Bodies are fixed strings on purpose — no user input, no serializer
 * dependency, nothing to escape.
 */
class ProblemAuthResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String UNAUTHORIZED_BODY = """
            {"type":"about:blank","title":"Unauthorized","status":401,\
            "detail":"Authentication via a bearer JWT from the configured issuer is required."}""";

    private static final String FORBIDDEN_BODY = """
            {"type":"about:blank","title":"Forbidden","status":403,\
            "detail":"The authenticated principal does not hold the role this operation requires."}""";

    private final BearerTokenAuthenticationEntryPoint bearer401 = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearer403 = new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        bearer401.commence(request, response, authException);
        writeProblem(response, UNAUTHORIZED_BODY);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        bearer403.handle(request, response, accessDeniedException);
        writeProblem(response, FORBIDDEN_BODY);
    }

    private static void writeProblem(HttpServletResponse response, String body) throws IOException {
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
