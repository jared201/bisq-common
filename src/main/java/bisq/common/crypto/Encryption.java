/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.common.crypto;

import bisq.common.util.Utilities;

import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO is Hmac needed/make sense?
public class Encryption {
    private static final Logger log = LoggerFactory.getLogger(Encryption.class);

    public static final String ASYM_KEY_ALGO = "RSA";
    private static final String ASYM_CIPHER = "RSA/None/OAEPWithSHA256AndMGF1Padding";

    private static final String SYM_KEY_ALGO = "AES";
    private static final String SYM_CIPHER = "AES";

    private static final String HMAC = "HmacSHA256";

    public static KeyPair generateKeyPair() {
        long ts = System.currentTimeMillis();
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(ASYM_KEY_ALGO, "BC");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.genKeyPair();
            log.trace("Generate msgEncryptionKeyPair needed {} ms", System.currentTimeMillis() - ts);
            return keyPair;
        } catch (Throwable e) {
            log.error(e.toString());
            e.printStackTrace();
            throw new RuntimeException("Could not create key.");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Symmetric
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static byte[] encrypt(byte[] payload, SecretKey secretKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(SYM_CIPHER, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            return cipher.doFinal(payload);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new CryptoException(e);
        }
    }

    public static byte[] decrypt(byte[] encryptedPayload, SecretKey secretKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(SYM_CIPHER, "BC");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return cipher.doFinal(encryptedPayload);
        } catch (Throwable e) {
            throw new CryptoException(e);
        }
    }

    public static SecretKey getSecretKeyFromBytes(byte[] secretKeyBytes) {
        return new SecretKeySpec(secretKeyBytes, 0, secretKeyBytes.length, SYM_KEY_ALGO);
    }

    public static byte[] getSecretKeyBytes(SecretKey secretKey) {
        return secretKey.getEncoded();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Hmac
    ///////////////////////////////////////////////////////////////////////////////////////////

    private static byte[] getPayloadWithHmac(byte[] payload, SecretKey secretKey) {
        byte[] payloadWithHmac;
        try {

            ByteArrayOutputStream outputStream = null;
            try {
                byte[] hmac = getHmac(payload, secretKey);
                outputStream = new ByteArrayOutputStream();
                outputStream.write(payload);
                outputStream.write(hmac);
                outputStream.flush();
                payloadWithHmac = outputStream.toByteArray().clone();
            } catch (IOException | NoSuchProviderException e) {
                log.error(e.toString());
                e.printStackTrace();
                throw new RuntimeException("Could not create hmac");
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            log.error(e.toString());
            e.printStackTrace();
            throw new RuntimeException("Could not create hmac");
        }
        return payloadWithHmac;
    }


    private static boolean verifyHmac(byte[] message, byte[] hmac, SecretKey secretKey) {
        try {
            byte[] hmacTest = getHmac(message, secretKey);
            return Arrays.equals(hmacTest, hmac);
        } catch (Throwable e) {
            log.error(e.toString());
            e.printStackTrace();
            throw new RuntimeException("Could not create cipher");
        }
    }

    private static byte[] getHmac(byte[] payload, SecretKey secretKey) throws NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException {
        Mac mac = Mac.getInstance(HMAC, "BC");
        mac.init(secretKey);
        return mac.doFinal(payload);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Symmetric with Hmac
    ///////////////////////////////////////////////////////////////////////////////////////////


    public static byte[] encryptPayloadWithHmac(byte[] payload, SecretKey secretKey) throws CryptoException {
        return encrypt(getPayloadWithHmac(payload, secretKey), secretKey);
    }

    public static byte[] decryptPayloadWithHmac(byte[] encryptedPayloadWithHmac, SecretKey secretKey) throws CryptoException {
        byte[] payloadWithHmac = decrypt(encryptedPayloadWithHmac, secretKey);
        String payloadWithHmacAsHex = Hex.toHexString(payloadWithHmac);
        // first part is raw message
        int length = payloadWithHmacAsHex.length();
        int sep = length - 64;
        String payloadAsHex = payloadWithHmacAsHex.substring(0, sep);
        // last 64 bytes is hmac
        String hmacAsHex = payloadWithHmacAsHex.substring(sep, length);
        if (verifyHmac(Hex.decode(payloadAsHex), Hex.decode(hmacAsHex), secretKey)) {
            return Hex.decode(payloadAsHex);
        } else {
            throw new CryptoException("Hmac does not match.");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Asymmetric
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static byte[] encryptSecretKey(SecretKey secretKey, PublicKey publicKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(ASYM_CIPHER, "BC");
            cipher.init(Cipher.WRAP_MODE, publicKey);
            return cipher.wrap(secretKey);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new CryptoException("Couldn't encrypt payload");
        }
    }

    public static SecretKey decryptSecretKey(byte[] encryptedSecretKey, PrivateKey privateKey) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance(ASYM_CIPHER, "BC");
            cipher.init(Cipher.UNWRAP_MODE, privateKey);
            return (SecretKey) cipher.unwrap(encryptedSecretKey, "AES", Cipher.SECRET_KEY);
        } catch (Throwable e) {
            // errors when trying to decrypt foreign network_messages are normal
            throw new CryptoException(e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Hybrid with signature of asymmetric key
    ///////////////////////////////////////////////////////////////////////////////////////////


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static SecretKey generateSecretKey() {
        try {
            KeyGenerator keyPairGenerator = KeyGenerator.getInstance(SYM_KEY_ALGO, "BC");
            keyPairGenerator.init(256);
            return keyPairGenerator.generateKey();
        } catch (Throwable e) {
            e.printStackTrace();
            log.error(e.getMessage());
            throw new RuntimeException("Couldn't generate key");
        }
    }

    public static byte[] getPublicKeyBytes(PublicKey encryptionPubKey) {
        return new X509EncodedKeySpec(encryptionPubKey.getEncoded()).getEncoded();
    }

    /**
     * @param encryptionPubKeyBytes
     * @return
     */
    public static PublicKey getPublicKeyFromBytes(byte[] encryptionPubKeyBytes) {
        try {
            return KeyFactory.getInstance(Encryption.ASYM_KEY_ALGO, "BC").generatePublic(new X509EncodedKeySpec(encryptionPubKeyBytes));
        } catch (InvalidKeySpecException | NoSuchAlgorithmException | NoSuchProviderException e) {
            log.error("Error creating sigPublicKey from bytes. sigPublicKeyBytes as hex={}, error={}", Utilities.bytesAsHexString(encryptionPubKeyBytes), e);
            e.printStackTrace();
            throw new KeyConversionException(e);
        }
    }
}

