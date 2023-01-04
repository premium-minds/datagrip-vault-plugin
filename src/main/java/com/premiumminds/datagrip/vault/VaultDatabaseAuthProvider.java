package com.premiumminds.datagrip.vault;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.database.access.DatabaseCredentials;
import com.intellij.database.dataSource.DatabaseAuthProvider;
import com.intellij.database.dataSource.DatabaseConnectionConfig;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VaultDatabaseAuthProvider implements DatabaseAuthProvider {

    private static final Logger logger = Logger.getInstance(VaultDatabaseAuthProvider.class);

    public static final String PROP_SECRET = "vault_secret";
    public static final String PROP_ADDRESS = "vault_address";
    public static final String PROP_TOKEN_FILE = "vault_token_file";
    private static final String ENV_VAULT_AGENT_ADDR = "VAULT_AGENT_ADDR";
    private static final String ENV_VAULT_ADDR = "VAULT_ADDR";
    private static final String ENV_VAULT_CONFIG_PATH = "VAULT_CONFIG_PATH";
    private static final String DEFAULT_VAULT_CONFIG_FILE = ".vault";
    private static final String DEFAULT_VAULT_TOKEN_FILE = ".vault-token";
    private static final String ERROR_VAULT_ADDRESS_NOT_DEFINED = "Vault address not defined";
    private static final String ERROR_VAULT_SECRET_NOT_DEFINED = "Vault secret not defined";
    private static final String ERROR_VAULT_TOKEN_NOT_DEFINED = "Vault token not defined";

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
    public boolean isApplicable(@NotNull LocalDataSource dataSource, @NotNull ApplicabilityLevel level) {
        return true;
    }

    @Override
    public @Nullable CompletionStage<@NotNull ProtoConnection> intercept(@NotNull ProtoConnection protoConnection, boolean b) {

        try {
            final var address = getAddress(protoConnection);
            final var secret = getSecret(protoConnection);
            final var token = getToken(protoConnection, address);

            logger.info("Address used: " + address);
            logger.info("Secret used: " + secret);

            final var uri = URI.create(address).resolve("/v1/").resolve(secret);

            final var request = HttpRequest.newBuilder()
                    .GET()
                    .header("X-Vault-Token", token)
                    .uri(uri)
                    .build();

            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("Problem connecting to Vault: " + response.body());
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
            throw new RuntimeException("Problem connecting to Vault: " + e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(protoConnection);
    }

    @Override
    public @Nullable AuthWidget createWidget(@Nullable Project project, @NotNull DatabaseCredentials credentials, @NotNull DatabaseConnectionConfig config) {
        return new VaultWidget();
    }

    private String getAddress(ProtoConnection protoConnection) {
        final var definedAddress = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_ADDRESS);
        if (definedAddress != null && !definedAddress.isBlank()) {
            return definedAddress;
        } else {
            final String vaultAgentAddrEnv = System.getenv(ENV_VAULT_AGENT_ADDR);
            if (vaultAgentAddrEnv != null && !vaultAgentAddrEnv.isBlank()){
                return vaultAgentAddrEnv;
            }
            final String vaultAddrEnv = System.getenv(ENV_VAULT_ADDR);
            if (vaultAddrEnv != null && !vaultAddrEnv.isBlank()){
                return vaultAddrEnv;
            }
        }
        throw new RuntimeException(ERROR_VAULT_ADDRESS_NOT_DEFINED);
    }

    private String getSecret(ProtoConnection protoConnection) {
        final var secret = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_SECRET);
        if (secret != null && !secret.isBlank()) {
            return secret;
        }
        throw new RuntimeException(ERROR_VAULT_SECRET_NOT_DEFINED);
    }


    private String getToken(ProtoConnection protoConnection, String vaultAddress) throws IOException, InterruptedException {

        final var tokenFile = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_TOKEN_FILE);
        if (tokenFile != null && !tokenFile.isBlank()) {
            final var path = Paths.get(tokenFile);
            if (path.toFile().exists()){
                return Files.readString(path);
            }
        }

        final var vaultConfigFile = getConfigFile();
        if (vaultConfigFile.toFile().exists()){
            final String token = getTokenFromVaultTokenHelper(vaultConfigFile, vaultAddress);
            if (token != null){
                return token;
            }
        }
        final var defaultTokenFilePath = Paths.get(System.getProperty("user.home"), DEFAULT_VAULT_TOKEN_FILE);
        if (defaultTokenFilePath.toFile().exists()){
            return Files.readString(defaultTokenFilePath);
        }

        throw new RuntimeException(ERROR_VAULT_TOKEN_NOT_DEFINED);
    }

    private Path getConfigFile(){
        Path vaultConfigPath = Paths.get(System.getProperty("user.home"), DEFAULT_VAULT_CONFIG_FILE) ;

        final String vaultConfigPathEnv = System.getenv(ENV_VAULT_CONFIG_PATH);
        if (vaultConfigPathEnv != null && !vaultConfigPathEnv.isBlank()){
            vaultConfigPath = Paths.get(vaultConfigPathEnv );
        }

        return vaultConfigPath;
    }

    private String getTokenFromVaultTokenHelper(Path configFile, String vaultAddress)
            throws IOException, InterruptedException
    {
        final Gson gson = new Gson();

        try (FileReader fileReader = new FileReader(configFile.toFile())) {
            final VaultConfig config = gson.fromJson(fileReader, VaultConfig.class);

            if (config.tokenHelper != null && !config.tokenHelper.isBlank()){
                final ProcessBuilder processBuilder = new ProcessBuilder();
                processBuilder.environment().putIfAbsent(ENV_VAULT_ADDR, vaultAddress);
                final Process process = processBuilder
                        .command(config.tokenHelper, "get")
                        .start();

                final StreamGobbler streamGobblerErr = new StreamGobbler(process.getErrorStream());
                final StreamGobbler streamGobblerOut = new StreamGobbler(process.getInputStream());

                streamGobblerErr.start();
                streamGobblerOut.start();

                if (!process.waitFor(10, TimeUnit.SECONDS)){
                    throw new RuntimeException("Failure running Vault Token Helper: " + config.tokenHelper + ", took too long to respond.");
                }

                streamGobblerOut.join();
                streamGobblerErr.join();

                if (streamGobblerErr.output != null && !streamGobblerErr.output.isBlank()){
                    throw new RuntimeException("Failure running Vault Token Helper: " + config.tokenHelper + ": " + streamGobblerErr.output);
                }
                return streamGobblerOut.output;
            }
        }
        return null;
    }

    private static class StreamGobbler extends Thread {

        private final InputStream stream;

        private String output;

        StreamGobbler(final InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {

            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream))) {
                output = bufferedReader.lines().collect(Collectors.joining());
            } catch (IOException e) {
                throw new RuntimeException("Problem reading from Vault Token Helper: " + e.getMessage(), e);
            }
        }
    }
}
