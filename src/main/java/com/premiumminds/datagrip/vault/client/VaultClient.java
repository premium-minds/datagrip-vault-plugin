package com.premiumminds.datagrip.vault.client;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public class VaultClient {

    private static final Logger logger = Logger.getInstance(VaultClient.class);
    public static final String X_VAULT_TOKEN = "X-Vault-Token";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Optional<String> getLease(String baseAddress, String token, String leaseId) throws IOException, InterruptedException {

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

        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            logger.info("No lease found for " + leaseId);
            return Optional.empty();
        }

        return Optional.of(gson.fromJson(response.body(), LeaseResponse.class)).map(LeaseResponse::getLeaseId);
    }

    public Credentials getCredentials(
            final String baseAddress,
            final String token,
            final String secret)
            throws IOException, InterruptedException
    {
        final var uri = URI.create(baseAddress).resolve("/v1/").resolve(secret);

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
}
