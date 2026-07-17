package com.premiumminds.vault.client;

import com.github.dockerjava.api.model.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class VaultClientTest {

    public static final String HASHICORP_VAULT_IMAGE = "hashicorp/vault:2.0";
    public static final String POSTGRES_IMAGE = "postgres:18";
    @TempDir
    Path tempDir;

    @Test
    void missingCertificateReturnsExplicitError() {
        final var missingCertificate = tempDir.resolve("missing-ca.pem");

        final var exception = assertThrows(IllegalArgumentException.class, () -> VaultClient.builder()
                .address("http://localhost:8200")
                .tokenLoader(() -> "root")
                .certificate(missingCertificate)
                .build());
        assertEquals("Vault certificate file does not exist: " + missingCertificate, exception.getMessage());
    }

    @Test
    void certificateDirectoryReturnsExplicitError() throws Exception {
        final var certificateDir = tempDir.resolve("certificate-dir");
        Files.createDirectory(certificateDir);

        final var exception = assertThrows(IllegalArgumentException.class, () -> VaultClient.builder()
                .address("http://localhost:8200")
                .tokenLoader(() -> "root")
                .certificate(certificateDir)
                .build());
        assertEquals("Vault certificate path is not a file: " + certificateDir, exception.getMessage());
    }

    @Test
    void dynamicCredentials() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();

        try (postgres; vault; network)
        {
            postgres.execInContainer("psql", "-U", "root", "-c", "CREATE ROLE ro NOINHERIT; GRANT SELECT ON ALL TABLES IN SCHEMA public TO ro;");
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable database
                        vault write database/config/postgresql \
                            plugin_name=postgresql-database-plugin \
                            connection_url="postgresql://{{username}}:{{password}}@postgres:5432/postgres?sslmode=disable" \
                            allowed_roles=readonly \
                            username="root" \
                            password="rootpassword"
                        vault write database/roles/readonly \\
                              db_name=postgresql \\
                              creation_statements="CREATE ROLE \\"{{name}}\\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' INHERIT; GRANT ro TO \\"{{name}}\\";" \
                              default_ttl=1h \\
                              max_ttl=24h
                        """
                    })
                    .build());

            final var vaultClient = VaultClient.builder()
                    .address("http://localhost:" + vault.getMappedPort(8200))
                    .tokenLoader(() -> "root")
                    .build();
            final var credentials = vaultClient.getCredentials(
                    "database/creds/readonly",
                    Request.dynamicRequest()
            );

            try (final var conn = DriverManager.getConnection(
                    String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                    credentials.username(),
                    credentials.password()
            )) {
                try (final var statement = conn.createStatement()) {
                    ResultSet rs = statement.executeQuery("SELECT version();");
                    if (rs.next()) {
                        assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                    } else {
                        fail("version query is empty");
                    }
                }
            }
        }
    }

    @Test
    void staticCredentials() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();

        try (postgres; vault; network)
        {
            postgres.execInContainer("psql", "-U", "root", "-c", "CREATE ROLE foo WITH LOGIN;");
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable database
                        vault write database/config/my-database \
                              plugin_name=postgresql-database-plugin \
                              connection_url="postgresql://{{username}}:{{password}}@postgres:5432/postgres?sslmode=disable" \
                              allowed_roles="my-role" \
                              username="root" \
                              password="rootpassword"
                        vault write database/static-roles/my-role \
                              db_name=my-database \
                              username="foo" \
                              rotation_schedule="0 * * * SAT"
                        """
                    })
                    .build());

            final var vaultClient = VaultClient.builder()
                    .address("http://localhost:" + vault.getMappedPort(8200))
                    .tokenLoader(() -> "root")
                    .build();
            final var credentials = vaultClient.getCredentials(
                    "database/static-creds/my-role",
                    Request.staticRequest()
            );

            try (final var conn = DriverManager.getConnection(
                    String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                    credentials.username(),
                    credentials.password()
            )) {
                try (final var statement = conn.createStatement()) {
                    ResultSet rs = statement.executeQuery("SELECT version();");
                    if (rs.next()) {
                        assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                    } else {
                        fail("version query is empty");
                    }
                }
            }
        }
    }

    @Test
    void kv1() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();

        try (postgres; vault; network)
        {
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable -path=kv1/data kv-v1
                        vault kv put kv1/data/postgres user="root" password="rootpassword"
                        """
                    })
                    .build());

            final var vaultClient = VaultClient.builder()
                    .address("http://localhost:" + vault.getMappedPort(8200))
                    .tokenLoader(() -> "root")
                    .build();
            final var credentials = vaultClient.getCredentials(
                    "kv1/data/postgres",
                    Request.kv1Request("user", "password")
            );

            try (final var conn = DriverManager.getConnection(
                    String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                    credentials.username(),
                    credentials.password()
            )) {
                try (final var statement = conn.createStatement()) {
                    ResultSet rs = statement.executeQuery("SELECT version();");
                    if (rs.next()) {
                        assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                    } else {
                        fail("version query is empty");
                    }
                }
            }
        }
    }

    @Test
    void kv2() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();

        try (postgres; vault; network)
        {
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable -path=kv2/data kv-v2
                        vault kv put kv2/data/postgres user="root" password="rootpassword"
                        """
                    })
                    .build());

            final var vaultClient = VaultClient.builder()
                    .address("http://localhost:" + vault.getMappedPort(8200))
                    .tokenLoader(() -> "root")
                    .build();
            final var credentials = vaultClient.getCredentials(
                    "kv2/data/data/postgres",
                    Request.kv2Request("user", "password")
            );

            try (final var conn = DriverManager.getConnection(
                    String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                    credentials.username(),
                    credentials.password()
            )) {
                try (final var statement = conn.createStatement()) {
                    ResultSet rs = statement.executeQuery("SELECT version();");
                    if (rs.next()) {
                        assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                    } else {
                        fail("version query is empty");
                    }
                }
            }
        }
    }

    @Test
    void selfSignedCertificate() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(Capability.IPC_LOCK))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withCommand("server -dev-tls -dev-tls-cert-dir=/tmp/")
                .withExposedPorts(8200);
        vault.start();

        final var vaultCA = tempDir.resolve("vault-ca.pem");
        vault.copyFileFromContainer("/tmp/vault-ca.pem", vaultCA.toString());

        try (postgres; vault; network)
        {
            postgres.execInContainer("psql", "-U", "root", "-c", "CREATE ROLE ro NOINHERIT; GRANT SELECT ON ALL TABLES IN SCHEMA public TO ro;");
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "https://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable -ca-path=/tmp/vault-ca.pem database
                        vault write -ca-path=/tmp/vault-ca.pem database/config/postgresql \
                            plugin_name=postgresql-database-plugin \
                            connection_url="postgresql://{{username}}:{{password}}@postgres:5432/postgres?sslmode=disable" \
                            allowed_roles=readonly \
                            username="root" \
                            password="rootpassword"
                        vault write -ca-path=/tmp/vault-ca.pem database/roles/readonly \\
                              db_name=postgresql \\
                              creation_statements="CREATE ROLE \\"{{name}}\\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' INHERIT; GRANT ro TO \\"{{name}}\\";" \
                              default_ttl=1h \\
                              max_ttl=24h
                        """
                    })
                    .build());

            final var vaultClient = VaultClient.builder()
                    .address("https://localhost:" + vault.getMappedPort(8200))
                    .certificate(vaultCA)
                    .tokenLoader(() -> "root")
                    .build();
            final var credentials = vaultClient.getCredentials(
                    "database/creds/readonly",
                    Request.dynamicRequest()
            );

            try (final var conn = DriverManager.getConnection(
                    String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                    credentials.username(),
                    credentials.password()
            )) {
                try (final var statement = conn.createStatement()) {
                    ResultSet rs = statement.executeQuery("SELECT version();");
                    if (rs.next()) {
                        assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                    } else {
                        fail("version query is empty");
                    }
                }
            }
        }
    }

    @Test
    void cache() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();
        try (postgres; vault; network)
        {
            postgres.execInContainer("psql", "-U", "root", "-c", "CREATE ROLE ro NOINHERIT; GRANT SELECT ON ALL TABLES IN SCHEMA public TO ro;");
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable database
                        vault write database/config/postgresql \
                            plugin_name=postgresql-database-plugin \
                            connection_url="postgresql://{{username}}:{{password}}@postgres:5432/postgres?sslmode=disable" \
                            allowed_roles=readonly \
                            username="root" \
                            password="rootpassword"
                        vault write database/roles/readonly \\
                              db_name=postgresql \\
                              creation_statements="CREATE ROLE \\"{{name}}\\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' INHERIT; GRANT ro TO \\"{{name}}\\";" \
                              default_ttl=1h \\
                              max_ttl=24h
                        """
                    })
                    .build());

            final var counter = new AtomicInteger(0);
            final var credentialsSet = new HashSet<>();

            for (int i = 0; i < 5; i++) {

                final var vaultClient = VaultClient.builder()
                        .cache(true)
                        .address("http://localhost:" + vault.getMappedPort(8200))
                        .tokenLoader(() -> {
                            counter.incrementAndGet();
                            return "root";
                        })
                        .build();

                final var credentials = vaultClient.getCredentials(
                        "database/creds/readonly",
                        Request.dynamicRequest()
                );
                credentialsSet.add(credentials);

                try (final var conn = DriverManager.getConnection(
                        String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                        credentials.username(),
                        credentials.password()
                )) {
                    try (final var statement = conn.createStatement()) {
                        ResultSet rs = statement.executeQuery("SELECT version();");
                        if (rs.next()) {
                            assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                        } else {
                            fail("version query is empty");
                        }
                    }
                }
            }
            assertEquals(5, counter.get());
            assertEquals(1, credentialsSet.size());
        }
    }

    @Test
    void noCache() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();
        try (postgres; vault; network)
        {
            postgres.execInContainer("psql", "-U", "root", "-c", "CREATE ROLE ro NOINHERIT; GRANT SELECT ON ALL TABLES IN SCHEMA public TO ro;");
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable database
                        vault write database/config/postgresql \
                            plugin_name=postgresql-database-plugin \
                            connection_url="postgresql://{{username}}:{{password}}@postgres:5432/postgres?sslmode=disable" \
                            allowed_roles=readonly \
                            username="root" \
                            password="rootpassword"
                        vault write database/roles/readonly \\
                              db_name=postgresql \\
                              creation_statements="CREATE ROLE \\"{{name}}\\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' INHERIT; GRANT ro TO \\"{{name}}\\";" \
                              default_ttl=1h \\
                              max_ttl=24h
                        """
                    })
                    .build());

            final var counter = new AtomicInteger(0);
            final var credentialsSet = new HashSet<>();

            for (int i = 0; i < 5; i++) {

                final var vaultClient = VaultClient.builder()
                        .cache(false)
                        .address("http://localhost:" + vault.getMappedPort(8200))
                        .tokenLoader(() -> {
                            counter.incrementAndGet();
                            return "root";
                        })
                        .build();

                final var credentials = vaultClient.getCredentials(
                        "database/creds/readonly",
                        Request.dynamicRequest()
                );
                credentialsSet.add(credentials);

                try (final var conn = DriverManager.getConnection(
                        String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                        credentials.username(),
                        credentials.password()
                )) {
                    try (final var statement = conn.createStatement()) {
                        ResultSet rs = statement.executeQuery("SELECT version();");
                        if (rs.next()) {
                            assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                        } else {
                            fail("version query is empty");
                        }
                    }
                }
            }
            assertEquals(5, counter.get());
            assertEquals(5, credentialsSet.size());
        }
    }

    @Test
    void cacheDifferentResponseKeys() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();
        try (postgres; vault; network)
        {
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable -path=kv2/data kv-v2
                        vault kv put kv2/data/postgres user="root" password="rootpassword"
                        """
                    })
                    .build());

            final var counter = new AtomicInteger(0);
            final var credentialsSet = new HashSet<>();

            for (int i = 0; i < 5; i++) {

                final var vaultClient = VaultClient.builder()
                        .cache(true)
                        .address("http://localhost:" + vault.getMappedPort(8200))
                        .tokenLoader(() -> {
                            counter.incrementAndGet();
                            return "root";
                        })
                        .build();

                final var credentials = vaultClient.getCredentials(
                        "kv2/data/data/postgres",
                        Request.kv2Request("user" + i, "password" + i)
                );
                credentialsSet.add(credentials);
            }
            assertEquals(5, counter.get());
            assertEquals(1, credentialsSet.size());
        }
    }

    @Test
    void dynamicCredentialsCacheWithLease() throws Exception {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer<>(DockerImageName.parse(HASHICORP_VAULT_IMAGE))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                // workaround for:
                // > unable to set CAP_SETFCAP effective capability: Operation not permitted
                // See: https://github.com/hashicorp/vault/issues/31919
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200);
        vault.start();

        try (postgres; vault; network)
        {
            postgres.execInContainer("psql", "-U", "root", "-c", "CREATE ROLE ro NOINHERIT; GRANT SELECT ON ALL TABLES IN SCHEMA public TO ro;");
            vault.execInContainer(ExecConfig.builder()
                    .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                    .command(new String[]{"sh", "-c", """
                        vault secrets enable database
                        vault write database/config/postgresql \
                            plugin_name=postgresql-database-plugin \
                            connection_url="postgresql://{{username}}:{{password}}@postgres:5432/postgres?sslmode=disable" \
                            allowed_roles=readonly \
                            username="root" \
                            password="rootpassword"
                        vault write database/roles/readonly \\
                              db_name=postgresql \\
                              creation_statements="CREATE ROLE \\"{{name}}\\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' INHERIT; GRANT ro TO \\"{{name}}\\";" \
                              default_ttl=1h \\
                              max_ttl=24h
                        """
                    })
                    .build());

            final var counter = new AtomicInteger(0);
            final var credentialsSet = new HashSet<>();

            for (int i = 0; i < 5; i++) {
                final var vaultClient = VaultClient.builder()
                        .address("http://localhost:" + vault.getMappedPort(8200))
                        .tokenLoader(() -> {
                            counter.incrementAndGet();
                            return "root";
                        })
                        .cache(true)
                        .build();
                final var credentials = vaultClient.getCredentials(
                        "database/creds/readonly",
                        Request.dynamicRequest()
                );
                credentialsSet.add(credentials);

                try (final var conn = DriverManager.getConnection(
                        String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)),
                        credentials.username(),
                        credentials.password()
                )) {
                    try (final var statement = conn.createStatement()) {
                        ResultSet rs = statement.executeQuery("SELECT version();");
                        if (rs.next()) {
                            assertTrue(rs.getString("version").startsWith("PostgreSQL"));
                        } else {
                            fail("version query is empty");
                        }
                    }
                }

                vault.execInContainer(ExecConfig.builder()
                        .envVars(Map.of("VAULT_ADDR", "http://127.0.0.1:8200", "VAULT_TOKEN", "root"))
                        .command(new String[]{"sh", "-c", "vault lease revoke -sync -prefix database/"})
                        .build());
            }
            assertEquals(9, counter.get());
            assertEquals(5, credentialsSet.size());
        }
    }

}
