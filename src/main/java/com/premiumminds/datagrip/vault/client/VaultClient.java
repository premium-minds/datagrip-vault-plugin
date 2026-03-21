package com.premiumminds.datagrip.vault.client;

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
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Optional;

public class VaultClient {

    private static final Logger logger = Logger.getInstance(VaultClient.class);
    private static final String X_VAULT_TOKEN = "X-Vault-Token";

    private final String address;
    private final Optional<Path> certificate;
    private final VaultTokenLoader vaultTokenLoader;

    private VaultClient(String address, Optional<Path> certificate, VaultTokenLoader vaultTokenLoader) {
        this.address = address;
        this.certificate = certificate;
        this.vaultTokenLoader = vaultTokenLoader;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String address;
        private Optional<Path> certificate;
        private VaultTokenLoader vaultTokenLoader;

        private Builder() {
        }

        public Builder withAddress(String address) {
            this.address = address;
            return this;
        }

        public Builder withCertificate(Optional<Path> certificate) {
            this.certificate = certificate;
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
            if (this.certificate == null) {
                throw new IllegalStateException("address is null");
            }
            if (this.vaultTokenLoader == null) {
                throw new IllegalStateException("address is null");
            }
            return new VaultClient(address, certificate, vaultTokenLoader);
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

        final var request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(leaseRequest)))
                .header(X_VAULT_TOKEN, token)
                .uri(uri)
                .build();

        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            logger.info("No lease found for " + leaseId);
            return Optional.empty();
        }

        return Optional.of(gson.fromJson(response.body(), LeaseResponse.class)).map(LeaseResponse::getLeaseId);
    }

    public Credentials getCredentials(
            final String secret)
            throws Exception
    {
        final var uri = URI.create(address).resolve("/v1/").resolve(secret);

        final var httpClient = getClient(certificate);
        final var token = vaultTokenLoader.get();

        final var request = HttpRequest.newBuilder()
                .GET()
                .header(X_VAULT_TOKEN, token)
                .uri(uri)
                .build();

        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("Problem connecting to Vault: " + response.body());
        }

        final var gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();

        final var dynamicSecret = gson.fromJson(response.body(), DynamicSecretResponse.class);
        return new Credentials(dynamicSecret.getData().getUsername(),
                dynamicSecret.getData().getPassword(),
                dynamicSecret.getLeaseId());
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

    private SSLContext getSSLContext(Path certificate) throws Exception {

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
