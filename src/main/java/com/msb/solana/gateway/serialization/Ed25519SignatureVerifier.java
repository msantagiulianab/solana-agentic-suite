package com.msb.solana.gateway.serialization;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.stereotype.Component;

@Component
public class Ed25519SignatureVerifier {

    public boolean verify(byte[] message, byte[] signature, byte[] publicKeyBytes) {
        if (message == null || signature == null || publicKeyBytes == null) {
            return false;
        }
        if (signature.length != 64 || publicKeyBytes.length != 32) {
            return false;
        }
        try {
            Ed25519PublicKeyParameters pubKeyParams = new Ed25519PublicKeyParameters(publicKeyBytes, 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, pubKeyParams);
            signer.update(message, 0, message.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean verifyBase58(byte[] message, String base58Signature, String base58PublicKey) {
        try {
            byte[] sigBytes = Base58.decode(base58Signature);
            byte[] pubKeyBytes = Base58.decode(base58PublicKey);
            return verify(message, sigBytes, pubKeyBytes);
        } catch (Exception e) {
            return false;
        }
    }
}