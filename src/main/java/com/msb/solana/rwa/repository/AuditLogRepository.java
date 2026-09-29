package com.msb.solana.rwa.repository;

import com.msb.solana.rwa.entity.AuditLog;
import com.msb.solana.rwa.entity.AuditLogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence access for the immutable {@link AuditLog} trail.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByWalletAddress(String walletAddress);

    List<AuditLog> findByWalletAddressAndStatus(String walletAddress, AuditLogStatus status);

    List<AuditLog> findByWalletAddressAndAction(String walletAddress, String action);

    List<AuditLog> findByTimestampAfter(Instant timestamp);

    Optional<AuditLog> findFirstByWalletAddressOrderByTimestampDesc(String walletAddress);

    long countByStatus(AuditLogStatus status);

    List<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);
}
