package io.github.essandhu.ledger.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PostingJpaRepository extends JpaRepository<PostingJpaEntity, UUID> {

    /**
     * One entry's legs, id-ascending — and id order IS leg order: the service allocates
     * posting ids positionally from the UUIDv7 generator ({@code JournalEntry.post} pairs legs
     * with ids BY POSITION), and {@code IdGeneratorConfig} makes sequential ids strictly
     * increasing by construction — a monotone-clamped clock plus atomic generation, because
     * JUG alone regresses across a backwards wall-clock step — so ascending ids reproduce
     * exactly the order the legs were validated and posted in. I11's exactness proof compares
     * reversal legs positionally and the API renders postings in this order, so leg order must
     * survive the round trip. Served by the {@code posting_entry} index.
     */
    List<PostingJpaEntity> findByEntryIdOrderByIdAsc(UUID entryId);
}
