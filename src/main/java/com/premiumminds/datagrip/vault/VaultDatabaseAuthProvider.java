package com.premiumminds.datagrip.vault;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import com.intellij.database.access.DatabaseCredentials;
import com.intellij.database.dataSource.DatabaseAuthProvider;
import com.intellij.database.dataSource.DatabaseConnectionConfig;
import com.intellij.database.dataSource.DatabaseConnectionPoint;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.premiumminds.vault.client.Credentials;
import com.premiumminds.vault.client.DefaultVaultTokenLoader;
import com.premiumminds.vault.client.Lease;
import com.premiumminds.vault.client.Request;
import com.premiumminds.vault.client.VaultClient;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VaultDatabaseAuthProvider implements DatabaseAuthProvider {

    private static final Logger logger = Logger.getInstance(VaultDatabaseAuthProvider.class);

    public static final String PROP_SECRET = "vault_secret";
    public static final String PROP_ADDRESS = "vault_address";
    public static final String PROP_CERTIFICATE = "vault_certificate";
    public static final String PROP_TOKEN_FILE = "vault_token_file";
    private static final String ENV_VAULT_AGENT_ADDR = "VAULT_AGENT_ADDR";
    private static final String ENV_VAULT_ADDR = "VAULT_ADDR";
    private static final String ENV_VAULT_CACERT = "VAULT_CACERT";
    private static final String ERROR_VAULT_ADDRESS_NOT_DEFINED = "Vault address not defined";
    private static final String ERROR_VAULT_SECRET_NOT_DEFINED = "Vault secret not defined";

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
        final var certificate = getCertificate(protoConnection);

        logger.info("Address used: " + address);
        logger.info("Secret used: " + secret);

        DefaultVaultTokenLoader vaultTokenLoader = new DefaultVaultTokenLoader(
                Optional.ofNullable(protoConnection.getConnectionPoint().getAdditionalProperty(PROP_TOKEN_FILE)).map(Path::of),
                address
        );

        final var credentialsRequest = Request.dynamicRequest();
        final var key = new CacheKey(address, secret);
        final var value = secretsCache.compute(key, (k, v) -> {
            final var vaultClient = VaultClient.builder()
                    .withAddress(address)
                    .withTokenLoader(vaultTokenLoader)
                    .withCertificate(certificate)
                    .build();
            try {
                if (v == null) {
                    return vaultClient.getCredentials(secret, credentialsRequest);
                } else {
                    if (v instanceof Lease lease) {
                        final var leaseOpt = vaultClient.getLease(lease.leaseId());
                        if (leaseOpt.isEmpty()) {
                            return vaultClient.getCredentials(secret, credentialsRequest);
                        }
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

    private @NotNull String getAddress(ProtoConnection protoConnection) {
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

    private @NotNull String getSecret(ProtoConnection protoConnection) {
        final var secret = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_SECRET);
        if (secret != null && !secret.isBlank()) {
            return secret;
        }
        throw new RuntimeException(ERROR_VAULT_SECRET_NOT_DEFINED);
    }

    private @Nullable Path getCertificate(ProtoConnection protoConnection) {
        final var definedCertificate = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_CERTIFICATE);
        if (definedCertificate != null && !definedCertificate.isBlank()) {
            return Path.of(definedCertificate);
        } else {
            final String vaultCertificateEnv = System.getenv(ENV_VAULT_CACERT);
            if (vaultCertificateEnv != null && !vaultCertificateEnv.isBlank()){
                return Path.of(vaultCertificateEnv);
            }
        }
        return null;
    }

}
