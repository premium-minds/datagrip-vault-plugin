package com.premiumminds.datagrip.vault;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.intellij.database.access.DatabaseCredentials;
import com.intellij.database.dataSource.DatabaseAuthProvider;
import com.intellij.database.dataSource.DatabaseConnectionConfig;
import com.intellij.database.dataSource.DatabaseConnectionPoint;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.premiumminds.datagrip.vault.client.Credentials;
import com.premiumminds.datagrip.vault.client.DefaultVaultTokenLoader;
import com.premiumminds.datagrip.vault.client.VaultClient;
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
    private static final String ERROR_VAULT_ADDRESS_NOT_DEFINED = "Vault address not defined";
    private static final String ERROR_VAULT_SECRET_NOT_DEFINED = "Vault secret not defined";

    private static final VaultClient  vaultClient = new VaultClient();

    private static final Map<CacheKey, Credentials> secretsCache = new ConcurrentHashMap<>();

    @Override
    public @NonNls @NotNull String getId() {
        return "vault";
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "Vault";
    }

    @NotNull
    @Override
    public ApplicabilityLevel.Result getApplicability(@NotNull DatabaseConnectionPoint point, @NotNull DatabaseAuthProvider.ApplicabilityLevel level) {
        return ApplicabilityLevel.Result.APPLICABLE;
    }

    @Override
    public @Nullable CompletionStage<ProtoConnection> intercept(@NotNull ProtoConnection protoConnection, boolean b) {

        final var address = getAddress(protoConnection);
        final var secret = getSecret(protoConnection);

        logger.info("Address used: " + address);
        logger.info("Secret used: " + secret);

        DefaultVaultTokenLoader vaultTokenLoader = new DefaultVaultTokenLoader(
                Optional.ofNullable(protoConnection.getConnectionPoint().getAdditionalProperty(PROP_TOKEN_FILE)).map(Path::of),
                address
        );

        CacheKey key = new CacheKey(address, secret);
        Credentials value = secretsCache.compute(key, (k,v) -> {
            try {
                if (v == null) {
                    return vaultClient.getCredentials(address, vaultTokenLoader, Optional.empty(), secret);
                } else {
                    final var lease = vaultClient.getLease(address, vaultTokenLoader, Optional.empty(), v.leaseId());
                    if (lease.isEmpty()) {
                        return vaultClient.getCredentials(address, vaultTokenLoader, Optional.empty(), secret);
                    }
                }
                return v;
            } catch (Exception e) {
                throw new RuntimeException("Problem connecting to Vault: " + e.getMessage(), e);
            }
        });

        logger.info("Username used " + value.username());

        protoConnection.getConnectionProperties().put("user", value.username());
        protoConnection.getConnectionProperties().put("password", value.password());

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
}
