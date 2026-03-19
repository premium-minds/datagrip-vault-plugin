package com.premiumminds.datagrip.vault.client;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class VaultClientTest {

    @Test
    void getCredentials() throws IOException, InterruptedException, SQLException {

        final var network = Network.newNetwork();

        final var postgres = new GenericContainer(DockerImageName.parse("postgres"))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "root")
                .withEnv("POSTGRES_PASSWORD", "rootpassword")
                .withExposedPorts(5432);
        postgres.start();

        var vault = new GenericContainer(DockerImageName.parse("hashicorp/vault"))
                .withNetwork(network)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root")
                .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
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
                              creation_statements="CREATE ROLE \\"{{name}}\\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' INHERIT; GRANT ro TO \\"{{name}}\\";"\s \
                              default_ttl=1h \\
                              max_ttl=24h
                        """
                    })
                    .build());

            var vaultClient = new VaultClient();
            final var credentials = vaultClient.getCredentials("http://localhost:" + vault.getMappedPort(8200), "root", "database/creds/readonly");

            Connection conn = DriverManager.getConnection(String.format("jdbc:postgresql://localhost:%s/postgres", postgres.getMappedPort(5432)), credentials.username(), credentials.password());
            final var statement = conn.createStatement();
            ResultSet rs = statement.executeQuery("SELECT version();");
            if(rs.next()){
                assertTrue(rs.getString("version").startsWith("PostgreSQL"));
            } else {
                fail("version query is empty");
            }
        }
    }

}
