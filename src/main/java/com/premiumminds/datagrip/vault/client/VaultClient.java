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
    public static final String X_VAULT_TOKEN = "X-Vault-Token";

    public Optional<String> getLease(
            final String baseAddress,
            final Optional<Path> certificate,
            final String token,
            final String leaseId)
            throws Exception
    {

        final var uri = URI.create(baseAddress).resolve("/v1/sys/leases/lookup");

        final var gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();

        final var leaseRequest = new LeaseRequest();
        leaseRequest.setLeaseId(leaseId);

        final var request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(leaseRequest)))
                .header(X_VAULT_TOKEN, token)
                .uri(uri)
                .build();

        final var httpClient = getClient(certificate);

        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            logger.info("No lease found for " + leaseId);
            return Optional.empty();
        }

        return Optional.of(gson.fromJson(response.body(), LeaseResponse.class)).map(LeaseResponse::getLeaseId);
    }

    public Credentials getCredentials(
            final String baseAddress,
            final Optional<Path> certificate,
            final String token,
            final String secret)
            throws Exception
    {
        final var uri = URI.create(baseAddress).resolve("/v1/").resolve(secret);

        final var httpClient = getClient(certificate);

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
