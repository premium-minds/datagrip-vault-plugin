package com.premiumminds.vault.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultVaultTokenLoaderTest {

    @TempDir
    Path tempDir;

    class TestDefaultVaultTokenLoader extends DefaultVaultTokenLoader{
        public TestDefaultVaultTokenLoader(Optional<Path> tokenFile, String vaultAddress) {
            super(tokenFile, vaultAddress);
        }

        @Override
        protected String getHome() {
            return tempDir.toString();
        }
    }

    @Test
    void empty() {
        final var loader = new TestDefaultVaultTokenLoader(java.util.Optional.of(Path.of("")), "http://localhost:8200");

        final var exception = assertThrows(Exception.class, loader::get);
        assertEquals("Vault token not defined", exception.getMessage());
    }

    @Test
    void tokenFileDirectoryReturnsExplicitError() throws Exception {
        final var tokenDir = tempDir.resolve("token-dir");
        Files.createDirectory(tokenDir);
        final var loader = new TestDefaultVaultTokenLoader(java.util.Optional.of(tokenDir), "http://localhost:8200");

        final var exception = assertThrows(IllegalArgumentException.class, loader::get);
        assertEquals("Vault token file path is not a file: " + tokenDir, exception.getMessage());
    }

    @Test
    void vaultConfigDirectoryReturnsExplicitError() throws Exception {
        final var vaultConfigDir = tempDir.resolve(".vault");
        Files.createDirectory(vaultConfigDir);

        final var loader = new TestDefaultVaultTokenLoader(java.util.Optional.empty(), "http://localhost:8200");
        final var exception = assertThrows(IllegalArgumentException.class, loader::get);

        assertEquals("Vault config file path is not a file: " + vaultConfigDir, exception.getMessage());
    }
}
