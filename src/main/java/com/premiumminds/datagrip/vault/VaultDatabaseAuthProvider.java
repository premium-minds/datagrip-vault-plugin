package com.premiumminds.datagrip.vault;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.GsonBuilder;
import com.intellij.database.access.DatabaseCredentials;
import com.intellij.database.dataSource.DatabaseAuthProvider;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VaultDatabaseAuthProvider implements DatabaseAuthProvider {

    private static final Logger logger = Logger.getInstance(VaultDatabaseAuthProvider.class);

    private static final String DEFAULT_VAULT_TOKEN_FILE = ".vault-token";

    public static final String PROP_SECRET = "vault_secret";
    public static final String PROP_ADDRESS = "vault_address";
    public static final String PROP_TOKEN_FILE = "vault_token_file";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public @NonNls @NotNull String getId() {
        return "vault";
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "Vault";
    }

    @Override
    public boolean isApplicable(@NotNull LocalDataSource localDataSource) {
        return true;
    }

    @Override
    public @Nullable CompletionStage<@NotNull ProtoConnection> intercept(@NotNull ProtoConnection protoConnection, boolean b) {

        final var address = protoConnection.getConnectionPoint().getAdditionalJdbcProperties().get(PROP_ADDRESS);
        final var secret = protoConnection.getConnectionPoint().getAdditionalJdbcProperties().get(PROP_SECRET);

        final var uri = URI.create(address).resolve("/v1/").resolve(secret);

        try {
            final var token = getTokenFilePath(protoConnection);

            final var request = HttpRequest.newBuilder()
                    .GET()
                    .header("X-Vault-Token", token)
                    .uri(uri)
                    .build();

            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException(response.body());
            } else {
                final var gson = new GsonBuilder()
                        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                        .create();
                final var secretResponse = gson.fromJson(response.body(), DynamicSecretResponse.class);

                logger.info("Username used " + secretResponse.getData().getUsername());

                protoConnection.getConnectionProperties().put("user", secretResponse.getData().getUsername());
                protoConnection.getConnectionProperties().put("password", secretResponse.getData().getPassword());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(protoConnection);
    }

    @Override
    public @Nullable AuthWidget createWidget(@Nullable Project project, @NotNull DatabaseCredentials credentials, @NotNull LocalDataSource dataSource) {
        return new VaultWidget();
    }

    private String getTokenFilePath(ProtoConnection protoConnection) throws IOException {
        Path path;
        final var tokenFile = protoConnection.getConnectionPoint().getAdditionalJdbcProperties().get(PROP_TOKEN_FILE);
        if (tokenFile != null && !tokenFile.isBlank()) {
            path = Paths.get(tokenFile);
        } else {
            path = Paths.get(System.getProperty("user.home"), DEFAULT_VAULT_TOKEN_FILE);
        }
        return Files.readAllLines(path).get(0);
    }
}
