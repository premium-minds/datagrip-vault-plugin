package com.premiumminds.datagrip.vault;

import com.premiumminds.vault.client.Request;

public record CacheKey(String address, String secret, SecretType secretType, Request request) {

}
