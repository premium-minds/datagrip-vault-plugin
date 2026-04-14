package com.premiumminds.vault.client;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public class VaultClient {

    private static final Logger logger = Logger.getInstance(VaultClient.class);
    private static final String X_VAULT_TOKEN = "X-Vault-Token";
    private static final String X_VAULT_NAMESPACE = "X-Vault-Namespace";

    private final String address;
    private final Optional<Path> certificate;
    private final Optional<String> namespace;
    private final VaultTokenLoader vaultTokenLoader;

    private VaultClient(String address, Optional<Path> certificate, Optional<String> namespace, VaultTokenLoader vaultTokenLoader) {
        this.address = address;
        this.certificate = certificate;
        this.namespace = namespace;
        this.vaultTokenLoader = vaultTokenLoader;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String address;
        private Path certificate;
        private String namespace;
        private VaultTokenLoader vaultTokenLoader;

        private Builder() {
        }

        public Builder withAddress(String address) {
            this.address = address;
            return this;
        }

        public Builder withCertificate(Path certificate) {
            this.certificate = certificate;
            return this;
        }
        public Builder withNamespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder withTokenLoader(VaultTokenLoader vaultTokenLoader) {
            this.vaultTokenLoader = vaultTokenLoader;
            return this;
        }

        public VaultClient build() {
            if (this.address == null) {
                throw new IllegalStateException("address is null");
            }
            if (this.vaultTokenLoader == null) {
                throw new IllegalStateException("vaultTokenLoader is null");
            }
            return new VaultClient(address,
                    Optional.ofNullable(certificate),
                    Optional.ofNullable(namespace),
                    vaultTokenLoader);
        }
    }

    public Optional<String> getLease(
            final String leaseId)
            throws Exception
    {

        final var uri = URI.create(address).resolve("/v1/sys/leases/lookup");

        final var gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();

        final var leaseRequest = new LeaseRequest();
        leaseRequest.setLeaseId(leaseId);

        final var httpClient = getClient(certificate);
        final var token = vaultTokenLoader.get();

        final var builder = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(leaseRequest)))
                .header(X_VAULT_TOKEN, token)
                .uri(uri);
        namespace.ifPresent(s -> builder.header(X_VAULT_NAMESPACE, s));
        final var request = builder.build();

        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            logger.info("No lease found for " + leaseId);
            return Optional.empty();
        }

        return Optional.of(gson.fromJson(response.body(), LeaseResponse.class)).map(LeaseResponse::getLeaseId);
    }

    public Credentials getCredentials(
            final String secret,
            final Request credentialsRequest)
            throws Exception
    {
        final var uri = URI.create(address).resolve("/v1/").resolve(secret);

        final var httpClient = getClient(certificate);
        final var token = vaultTokenLoader.get();

        final var builder = HttpRequest.newBuilder()
                .GET()
                .header(X_VAULT_TOKEN, token)
                .uri(uri);
        namespace.ifPresent(s -> builder.header(X_VAULT_NAMESPACE, s));
        final var request = builder.build();

        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("Problem connecting to Vault: " + response.body());
        }

        final var gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();

        // TODO: replace with JEP 441: Pattern Matching for switch in Java 21
        final var vaultResponse = gson.fromJson(response.body(), VaultResponse.class);
        if (credentialsRequest instanceof Request.StaticRequest) {
            final var username = requireStringValue(vaultResponse.getData(), "username", secret, "STATIC_ROLE");
            final var password = requireStringValue(vaultResponse.getData(), "password", secret, "STATIC_ROLE");
            return new Response(username, password);
        } else if (credentialsRequest instanceof Request.DynamicRequest) {
            final var username = requireStringValue(vaultResponse.getData(), "username", secret, "DYNAMIC_ROLE");
            final var password = requireStringValue(vaultResponse.getData(), "password", secret, "DYNAMIC_ROLE");
            return new ResponseWithLease(username, password, vaultResponse.getLeaseId());
        } else if (credentialsRequest instanceof Request.KV1Request kv1Request) {
            validateConfiguredKey(kv1Request.userKey(), "username", secret, "KV1");
            validateConfiguredKey(kv1Request.passKey(), "password", secret, "KV1");
            final var username = requireStringValue(vaultResponse.getData(), kv1Request.userKey(), secret, "KV1");
            final var password = requireStringValue(vaultResponse.getData(), kv1Request.passKey(), secret, "KV1");
            return new Response(username, password);
        } else if (credentialsRequest instanceof Request.KV2Request kv2Request) {
            validateConfiguredKey(kv2Request.userKey(), "username", secret, "KV2");
            validateConfiguredKey(kv2Request.passKey(), "password", secret, "KV2");
            final var data = requireNestedDataMap(vaultResponse.getData(), secret, "KV2");
            final var username = requireStringValue(data, kv2Request.userKey(), secret, "KV2");
            final var password = requireStringValue(data, kv2Request.passKey(), secret, "KV2");
            return new Response(username, password);
        } else {
            throw new IllegalStateException("Unknown request type: " + credentialsRequest.getClass().getName());
        }
    }

    private static void validateConfiguredKey(String configuredKey, String fieldName, String secret, String secretType) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Vault secret '" + secret + "' requires a non-empty " + fieldName + " key configuration"
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireNestedDataMap(Map<String, Object> data, String secret, String secretType) {
        final var nestedData = data.get("data");
        if (!(nestedData instanceof Map<?, ?> nestedMap)) {
            throw new IllegalArgumentException(
                    "Vault secret '" + secret + "' does not contain the expected nested 'data' object"
            );
        }
        return (Map<String, Object>) nestedMap;
    }

    private static String requireStringValue(Map<String, ?> data, String key, String secret, String secretType) {
        final var value = data.get(key);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new IllegalArgumentException(
                    "Vault secret '" + secret + "' does not contain a value for key '" + key
                            + "'. Available keys: " + formatKeys(data.keySet())
            );
        }
        return stringValue;
    }

    private static String formatKeys(Collection<?> keys) {
        return keys.toString();
    }

    private record Response(String username, String password) implements Credentials {
    }

    private record ResponseWithLease(String username, String password, String leaseId) implements Credentials, Lease {
    }

    private HttpClient getClient(Optional<Path> certificate) throws Exception {

        var builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10));
        if (certificate.isPresent()) {
            builder.sslContext(getSSLContext(certificate.get()));
        }
        return builder.build();
    }

    static void validateCertificatePath(Path certificate) {
        if (!Files.exists(certificate)) {
            throw new IllegalArgumentException("Vault certificate file does not exist: " + certificate);
        }
        if (!Files.isRegularFile(certificate)) {
            throw new IllegalArgumentException("Vault certificate path is not a file: " + certificate);
        }
    }

    private SSLContext getSSLContext(Path certificate) throws Exception {
        validateCertificatePath(certificate);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        try (InputStream is = new FileInputStream(certificate.toFile())) {
            Certificate cert = cf.generateCertificate(is);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("vault-cert", cert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());

            return sslContext;
        }
    }
}
