package io.github.essandhu.ledger.adapter.web;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A bearer token that authenticated but carries no usable {@code sub} claim. RFC 7519 makes
 * {@code sub} optional, so a misconfigured issuer can mint such a token and the resource server
 * will accept it — but every write here records {@code created_by} = JWT subject, so
 * without one the request cannot be attributed. Unguarded, {@code jwt.getSubject()} returning
 * {@code null} would NPE into a 500 at the command constructor; this is the loud, correct
 * refusal instead.
 *
 * <p>Mapped to <b>401</b>, not 400: the request body may be flawless — it is the CREDENTIAL
 * that is defective, and 401 is the status doctrine for that (the security layer's own
 * ProblemAuthResponses posture). Guarded at the controller, where the subject is consumed,
 * rather than in a decoder validator: the boundary that needs the claim states the need, and
 * the guard sits on the same code path the integration tests drive. A web-layer contract, like
 * {@link FieldNotWritable} — the domain never sees these attempts.
 */
final class MissingTokenSubject extends RuntimeException {

    MissingTokenSubject() {
        super("the bearer token carries no subject (sub) claim; writes are attributed to it");
    }

    /** The one guard all write controllers route the principal through. */
    static String requiredSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new MissingTokenSubject();
        }
        return subject;
    }
}
