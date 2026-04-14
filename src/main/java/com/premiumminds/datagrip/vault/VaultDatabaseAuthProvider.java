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
    public static final String PROP_NAMESPACE = "vault_namespace";
    public static final String PROP_TOKEN_FILE = "vault_token_file";
    public static final String PROP_SECRET_TYPE = "secret_type";
    public static final String PROP_USERNAME_KEY = "username_key";
    public static final String PROP_PASSWORD_KEY = "password_key";
    private static final String ENV_VAULT_AGENT_ADDR = "VAULT_AGENT_ADDR";
    private static final String ENV_VAULT_ADDR = "VAULT_ADDR";
    private static final String ENV_VAULT_CACERT = "VAULT_CACERT";
    private static final String ENV_VAULT_NAMESPACE = "VAULT_NAMESPACE";
    private static final String ERROR_VAULT_ADDRESS_NOT_DEFINED = "Vault address not defined";
    private static final String ERROR_VAULT_SECRET_NOT_DEFINED = "Vault secret not defined";
    private static final String DEFAULT_USERNAME_KEY = "username";
    private static final String DEFAULT_PASSWORD_KEY = "password";

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
        final var namespace = getNamespace(protoConnection);
        final var secretType = getSecretType(protoConnection);
        final var usernameKey = getUsernameKey(protoConnection);
        final var passwordKey = getPasswordKey(protoConnection);

        logger.info("Address used: " + address);
        logger.info("Secret used: " + secret);

        DefaultVaultTokenLoader vaultTokenLoader = new DefaultVaultTokenLoader(
                getTokenFile(protoConnection),
                address
        );

        final Request credentialsRequest = switch (secretType) {
            case DYNAMIC_ROLE -> Request.dynamicRequest();
            case STATIC_ROLE -> Request.staticRequest();
            case KV1 -> Request.kv1Request(usernameKey, passwordKey);
            case KV2 -> Request.kv2Request(usernameKey, passwordKey);
        };
        final var key = new CacheKey(address, secret, secretType, usernameKey, passwordKey);
        logger.info("Cache key used: " + key);

        final var value = secretsCache.compute(key, (k, v) -> {
            final var vaultClient = VaultClient.builder()
                    .withAddress(address)
                    .withTokenLoader(vaultTokenLoader)
                    .withCertificate(certificate)
                    .withNamespace(namespace)
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

    private @Nullable String getNamespace(ProtoConnection protoConnection) {
        final var definedNamespace = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_NAMESPACE);
        if (definedNamespace != null && !definedNamespace.isBlank()) {
            return definedNamespace;
        } else {
            final String vaultNamespaceEnv = System.getenv(ENV_VAULT_NAMESPACE);
            if (vaultNamespaceEnv != null && !vaultNamespaceEnv.isBlank()){
                return vaultNamespaceEnv;
            }
        }
        return null;
    }

    private @NotNull SecretType getSecretType(ProtoConnection protoConnection) {
        final var definedSecretType = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_SECRET_TYPE);
        if (definedSecretType != null && !definedSecretType.isBlank()) {
            return SecretType.valueOf(definedSecretType);
        }
        return SecretType.DYNAMIC_ROLE;
    }

    private @Nullable String getUsernameKey(ProtoConnection protoConnection) {
        final var configuredKey = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_USERNAME_KEY);
        if (configuredKey == null || configuredKey.isBlank()) {
            return DEFAULT_USERNAME_KEY;
        }
        return configuredKey;
    }

    private @Nullable String getPasswordKey(ProtoConnection protoConnection) {
        final var configuredKey = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_PASSWORD_KEY);
        if (configuredKey == null || configuredKey.isBlank()) {
            return DEFAULT_PASSWORD_KEY;
        }
        return configuredKey;
    }

    private @NotNull Optional<Path> getTokenFile(ProtoConnection protoConnection) {
        final var configuredTokenFile = protoConnection.getConnectionPoint().getAdditionalProperty(PROP_TOKEN_FILE);
        if (configuredTokenFile == null || configuredTokenFile.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(configuredTokenFile));
    }

}
