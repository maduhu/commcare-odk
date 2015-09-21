package org.commcare.android.util;

import org.spongycastle.jce.provider.BouncyCastleProvider;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;

/**
 * A set of helper methods for verifying whether a message was genuinely sent from HQ
 *
 * Created by wpride1 on 9/11/15.
 */
public class SigningUtil {

    /**
     *
     * @param publicKeyString the known public key of CCHQ
     * @param message the message content
     * @param messageSignature the signature generated by HQ with its private key and the message content
     * @return whether or not the message was verified to be sent with HQ's private key
     */
    public static boolean verifyMessageSignatureHelper(String publicKeyString, String message, String messageSignature) {
        try {
            PublicKey publicKey = getPublicKey(publicKeyString);
            return verifyMessageSignature(publicKey, message, messageSignature);
        } catch (Exception e) {
            // a bunch of exceptions can be thrown from the crypto methods. I mostly think we just
            // care that we couldn't verify it
            e.printStackTrace();
        }
        return false;
    }

    private static PublicKey getPublicKey(String key) throws Exception{
        byte[] derPublicKey = Base64.decode(key);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(derPublicKey);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    private static boolean verifyMessageSignature(PublicKey publicKey, String messageString, String signature) throws SignatureException, NoSuchAlgorithmException, Base64DecoderException, InvalidKeyException {
        Signature sign = Signature.getInstance("SHA256withRSA/PSS", new BouncyCastleProvider());
        byte[] signature_binary = Base64.decode(signature);
        byte[] message = messageString.getBytes();
        sign.initVerify(publicKey);
        sign.update(message);
        return sign.verify(signature_binary);
    }
}
