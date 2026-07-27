package io.github.essandhu.ledger.console.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * API failures rendered honestly (the M8b presentation contract): the ledger's RFC 9457
 * problem body is surfaced — status, title, detail — never flattened into a generic "something
 * went wrong", and the console's own response carries the SAME status the API answered. Each
 * class of failure reads differently on the page: 401 (the relayed token was rejected), 403
 * (the role matrix said no), 404 (nothing at that id), 400 (the request shape), 422 (a ledger
 * rule), 5xx/unreachable (the ledger itself).
 *
 * <p>htmx requests get the fragment; when the swap target is the statement's load-more
 * sentinel (a table row), the row-shaped variant keeps the swap table-legal. The client-side
 * half of the contract lives in app.js, which re-enables the 4xx/5xx swaps htmx refuses by
 * default.
 */
@ControllerAdvice
class ConsoleErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ConsoleErrorAdvice.class);

    @ExceptionHandler(RestClientResponseException.class)
    String apiError(
            RestClientResponseException exception,
            // Read manually: @RequestHeader is not a supported @ExceptionHandler parameter —
            // an unresolvable argument makes the advice itself fail and the original
            // exception escapes as a raw 500.
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        ProblemDetail problem = safeProblem(exception);
        int status = exception.getStatusCode().value();
        response.setStatus(status);
        model.addAttribute("status", status);
        model.addAttribute("heading", headingFor(status));
        model.addAttribute("title", problem != null && problem.getTitle() != null
                ? problem.getTitle() : exception.getStatusText());
        model.addAttribute("detail", problem != null ? problem.getDetail() : null);
        model.addAttribute("type", problem != null && problem.getType() != null
                && !"about:blank".equals(problem.getType().toString())
                        ? problem.getType().toString() : null);
        return errorView(request);
    }

    @ExceptionHandler(ResourceAccessException.class)
    String apiUnreachable(
            ResourceAccessException exception,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        // The cause (with the internal URL it names) belongs in the log, not the browser.
        log.warn("ledger API unreachable", exception);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        model.addAttribute("status", 503);
        model.addAttribute("heading", "Ledger unreachable");
        model.addAttribute("title", "The console could not reach the ledger service");
        model.addAttribute("detail", "The stack may be down — `docker compose up` brings it back.");
        model.addAttribute("type", null);
        return errorView(request);
    }

    /**
     * The relay could not produce a token — typically a refresh that failed after Keycloak
     * killed the session server-side. {@code ClientAuthorizationRequiredException} must keep
     * propagating (Security's redirect-to-login filter handles it); anything else renders as
     * the session problem it is.
     */
    @ExceptionHandler(ClientAuthorizationException.class)
    String authorizationFailed(
            ClientAuthorizationException exception,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        if (exception instanceof ClientAuthorizationRequiredException required) {
            throw required;
        }
        log.info("token relay could not authorize the API call: {}",
                exception.getError().getErrorCode());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        model.addAttribute("status", 401);
        model.addAttribute("heading", "Console session expired");
        model.addAttribute("title", "The Keycloak session behind this console session is gone");
        model.addAttribute("detail", "Sign out and back in to start a fresh session.");
        model.addAttribute("type", null);
        return errorView(request);
    }

    /** Fragment for htmx, row-shaped when the swap target is the statement sentinel row. */
    private static String errorView(HttpServletRequest request) {
        if (!"true".equals(request.getHeader("HX-Request"))) {
            return "error";
        }
        String target = request.getHeader("HX-Target");
        return target != null && target.startsWith("more-")
                ? "error-row :: problemRow"
                : "error :: problem";
    }

    private static ProblemDetail safeProblem(RestClientResponseException exception) {
        try {
            return exception.getResponseBodyAs(ProblemDetail.class);
        } catch (RuntimeException notAProblemBody) {
            return null;
        }
    }

    private static String headingFor(int status) {
        return switch (status) {
            case 400 -> "The ledger rejected the request shape";
            case 401 -> "The ledger rejected the session token";
            case 403 -> "Not permitted for this account's roles";
            case 404 -> "Nothing at this address";
            case 422 -> "Rejected by a ledger rule";
            default -> status >= 500 ? "The ledger failed" : "Unexpected answer from the ledger";
        };
    }
}
