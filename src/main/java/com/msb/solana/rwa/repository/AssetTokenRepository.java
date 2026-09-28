package com.msb.solana.rwa.repository;

import com.msb.solana.rwa.entity.AssetToken;
import com.msb.solana.rwa.entity.AssetTokenComplianceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence access for {@link AssetToken} records.
 */
public interface AssetTokenRepository extends JpaRepository<AssetToken, UUID> {

    Optional<AssetToken> findByMintAddress(String mintAddress);

    Optional<AssetToken> findByIdempotencyKey(String idempotencyKey);

    List<AssetToken> findByComplianceStatus(AssetTokenComplianceStatus complianceStatus);

    @Query("select t from AssetToken t where t.mintAddress is not null")
    List<AssetToken> findAssetTokensWithMintAddress();
}
