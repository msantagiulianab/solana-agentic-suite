-- ============================================================================
-- V4__seed_compliance_demo_data.sql
-- Baseline seed data for x402 compliance demo and test verification.
-- ============================================================================

INSERT INTO investors (
    id, full_name, email, wallet_address, kyc_status, country, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'Alice Verified',
    'alice@verified.com',
    'DoD8TaZaTENh68nkBwZDH4ovRYBwbeTEYATUEDtHT98v',
    'VERIFIED',
    'US',
    NOW(),
    NOW()
)
ON CONFLICT (wallet_address) DO UPDATE SET
    kyc_status = 'VERIFIED',
    updated_at = NOW();

INSERT INTO asset_tokens (
    id, asset_name, valuation_usd, mint_address, compliance_status, settlement_status, created_at, updated_at
) VALUES (
    gen_random_uuid(),
    'Treasury Bill 2026',
    1000000.00,
    'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA',
    'COMPLIANT',
    'FINALIZED',
    NOW(),
    NOW()
)
ON CONFLICT (mint_address) DO UPDATE SET
    compliance_status = 'COMPLIANT',
    settlement_status = 'FINALIZED',
    updated_at = NOW();
