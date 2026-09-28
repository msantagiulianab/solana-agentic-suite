package com.msb.solana.gateway.repository;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA repository integration tests for {@link PaymentAuditRepository} (H2).
 * Exercises the V1 Flyway schema, the generated identity id, and the
 * database-level anti-replay unique constraint on (channel_id, nonce).
 */
@DataJpaTest
@ActiveProfiles("test")
class PaymentAuditRepositoryTest {

    @Autowired
    private PaymentAuditRepository repository;

    private PaymentAuditRecord record(String channelId, long nonce) {
        return PaymentAuditRecord.create(
                channelId,
                "payer_" + channelId,
                5000L,
                nonce,
                "signature_" + channelId + "_" + nonce,
                PaymentAuditStatus.VERIFIED
        );
    }

    @Test
    @DisplayName("Should persist an append-only record with generated id and created_at")
    void save_persistsRecordWithGeneratedIdAndCreatedAt() {
        PaymentAuditRecord saved = repository.save(record("repo_chan_1", 1L));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getChannelId()).isEqualTo("repo_chan_1");
        assertThat(saved.getCumulativeAmountAtomic()).isEqualTo(5000L);
        assertThat(saved.getStatus()).isEqualTo(PaymentAuditStatus.VERIFIED);
    }

    @Test
    @DisplayName("Should find records scoped to a channel id")
    void findByChannelId_returnsOnlyRowsForThatChannel() {
        repository.save(record("repo_chan_2", 1L));
        repository.save(record("repo_chan_2", 2L));
        repository.save(record("repo_chan_other", 1L));

        assertThat(repository.findByChannelId("repo_chan_2")).hasSize(2);
    }

    @Test
    @DisplayName("Should find records by payer pubkey")
    void findByPayerPubkey_returnsMatchingRows() {
        repository.save(record("repo_chan_3", 1L));

        assertThat(repository.findByPayerPubkey("payer_repo_chan_3")).hasSize(1);
    }

    @Test
    @DisplayName("Should reject a duplicate (channel_id, nonce) at the database constraint level")
    void save_duplicateChannelAndNonce_violatesUniqueConstraint() {
        repository.save(record("repo_chan_4", 1L));

        assertThatThrownBy(() -> repository.saveAndFlush(record("repo_chan_4", 1L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should detect an existing nonce via the derived exists query")
    void existsByChannelIdAndNonce_detectsReplay() {
        repository.save(record("repo_chan_5", 1L));

        assertThat(repository.existsByChannelIdAndNonce("repo_chan_5", 1L)).isTrue();
        assertThat(repository.existsByChannelIdAndNonce("repo_chan_5", 2L)).isFalse();
    }
}
