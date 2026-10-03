package com.gyejoldanji.global.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import com.gyejoldanji.global.config.properties.AuthProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 자체 JWT(RS256) 서명 키쌍을 기동 시 읽고 검증한다.
 *
 * <p>개인키는 PKCS#8 PEM({@code PRIVATE KEY}), 공개키는 X.509 SubjectPublicKeyInfo PEM({@code PUBLIC KEY})만 받는다. 둘 다
 * RSA 2048비트 이상이어야 하고, 실제 서명·검증으로 같은 키쌍인지 확인한다. 하나라도 어긋나면 Bean 생성이 실패해 애플리케이션이
 * 시작되지 않는다. 오류에는 파일 경로와 원인 종류만 남기고 키 내용은 넣지 않는다.
 */
@Configuration
public class JwtKeyConfig {

    private static final int MIN_RSA_BITS = 2048;

    /** 설정한 PEM 파일에서 검증된 RSA 키쌍을 만든다. */
    @Bean
    public KeyPair jwtKeyPair(AuthProperties properties) {
        RSAPrivateKey privateKey = readPrivateKey(properties.getJwt().getPrivateKeyPath());
        RSAPublicKey publicKey = readPublicKey(properties.getJwt().getPublicKeyPath());
        if (privateKey.getModulus().bitLength() < MIN_RSA_BITS || publicKey.getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IllegalStateException("JWT 키는 RSA " + MIN_RSA_BITS + "비트 이상이어야 합니다.");
        }
        if (!isSameKeyPair(privateKey, publicKey)) {
            throw new IllegalStateException("JWT 개인키와 공개키가 같은 키쌍이 아닙니다.");
        }
        return new KeyPair(publicKey, privateKey);
    }

    private static RSAPrivateKey readPrivateKey(String location) {
        try {
            byte[] der = readPem(location, "PRIVATE KEY");
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw unreadable("개인키(PKCS#8 PEM RSA)", location, e);
        }
    }

    private static RSAPublicKey readPublicKey(String location) {
        try {
            byte[] der = readPem(location, "PUBLIC KEY");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw unreadable("공개키(X.509 SubjectPublicKeyInfo PEM RSA)", location, e);
        }
    }

    /** 정확히 한 종류의 PEM 블록만 받는다. 다른 헤더(PKCS#1·인증서 등)·여러 블록·잘못된 Base64는 거부한다. */
    private static byte[] readPem(String location, String type) throws IOException {
        String pem = Files.readString(Path.of(location)).strip();
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) {
            throw new IllegalArgumentException("Unexpected PEM type");
        }
        String body = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /** 원인 메시지에 키 내용이 섞일 수 있으므로 원인은 종류만 남긴다. */
    private static IllegalStateException unreadable(String what, String location, Exception cause) {
        return new IllegalStateException(
                "JWT " + what + " 파일을 읽을 수 없습니다: " + location + " (" + cause.getClass().getSimpleName() + ")");
    }

    /** JWT RS256과 같은 SHA256withRSA로 서명하고 공개키로 검증한다. */
    private static boolean isSameKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        byte[] probe = "jwt-key-pair-check".getBytes(StandardCharsets.UTF_8);
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(probe);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signer.sign());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
