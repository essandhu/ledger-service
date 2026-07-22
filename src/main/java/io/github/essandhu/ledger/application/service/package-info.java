/**
 * Use-case implementations and the transaction boundary ({@code @Transactional} lives here and
 * only here). Orchestrates domain objects through the out-ports; contains no web, JPA, or batch
 * types.
 */
package io.github.essandhu.ledger.application.service;
