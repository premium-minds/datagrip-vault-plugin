package com.premiumminds.datagrip.vault;

import org.jetbrains.annotations.Nullable;

public record CacheKey(
        String address,
        String secret,
        SecretType secretType,
        @Nullable String usernameKey,
        @Nullable String passwordKey
) {

}
