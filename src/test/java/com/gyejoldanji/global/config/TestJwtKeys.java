package com.gyejoldanji.global.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;

/** 테스트 실행 중 만든 키를 임시 PEM 파일로 쓰고 필수 auth.jwt 설정을 만든다. 키 내용은 출력하거나 저장소에 두지 않는다. */
public final class TestJwtKeys {

    private TestJwtKeys() {
    }

    /** 실제 KeyPairGenerator로 키쌍을 만든다. */
    public static KeyPair generate(String algorithm, int size) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(size);
        return generator.generateKeyPair();
    }

    /** DER 바이트를 지정한 종류의 PEM으로 감싸 {@code dir} 안의 임시 파일에 쓴다. */
    public static Path writePem(Path dir, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        Path file = Files.createTempFile(dir, "jwt-", ".pem");
        file.toFile().deleteOnExit();
        return Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }

    /** 키 경로를 포함한 필수 auth.jwt 설정을 {@code key=value} 목록으로 만든다. */
    public static String[] properties(Path privateKey, Path publicKey) {
        return new String[] {
                "auth.jwt.issuer=test-issuer",
                "auth.jwt.audience=test-audience",
                "auth.jwt.key-id=test-key",
                "auth.jwt.private-key-path=" + privateKey,
                "auth.jwt.public-key-path=" + publicKey};
    }

    /** 새 RSA 2048 키쌍을 임시 파일로 만들어 전체 컨텍스트 테스트에 필수 auth.jwt 설정으로 공급한다. */
    public static void register(DynamicPropertyRegistry registry) throws GeneralSecurityException, IOException {
        Path dir = Files.createTempDirectory("jwt-test-keys");
        dir.toFile().deleteOnExit();
        KeyPair pair = generate("RSA", 2048);
        String[] properties = properties(
                writePem(dir, "PRIVATE KEY", pair.getPrivate().getEncoded()),
                writePem(dir, "PUBLIC KEY", pair.getPublic().getEncoded()));
        for (String property : properties) {
            String[] keyValue = property.split("=", 2);
            registry.add(keyValue[0], () -> keyValue[1]);
        }
    }
}
