package io.github.essandhu.ledger.adapter.web;

/**
 * A request carried a recognized field that this operation must not write (type/currency/
 * allowNegative on PATCH — immutable per the API contract — or status on POST). Rejected loudly with
 * 422 rather than silently ignored: a client that believes it changed an account's type must
 * find out. Unrecognized junk fields, by contrast, follow the usual lenient-JSON posture.
 * A web-layer contract, not a domain rule — the domain never even sees these attempts.
 */
class FieldNotWritable extends RuntimeException {

    FieldNotWritable(String field, String operation) {
        super("field '%s' is not writable via %s".formatted(field, operation));
    }
}
