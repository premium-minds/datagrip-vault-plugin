## Vault Agent

In previous versions, the recommended way to use this plugin was with a [Vault Agent](https://www.vaultproject.io/docs/agent), with [Auto-Auth](https://www.vaultproject.io/docs/agent/autoauth) and [cache](https://www.vaultproject.io/docs/agent/caching) enabled.

This is an example, with [AWS Authenticaton](https://www.vaultproject.io/docs/auth/aws). Save it as `vault-agent-datagrip.hcl` and edit accordingly:
```hcl
auto_auth {
    method "aws" {
        config = {
            type = "iam"
            role = "zzz"
            access_key = "xxx"
            secret_key = "yyy"
            header_value = "https://vault.example.com"
        }  
    }

    sink "file" {
        config = {
            path = "/opt/vault/vault-token-datagrip"
        }
    }
}

vault {
    address = "https://vault.example.com"
}

cache {  
    use_auto_auth_token = true
}

listener "tcp" {
    address = "127.0.0.1:8101"
    tls_disable = true
}
```

Launch the Vault Agent with `vault agent -log-level=debug -config vault-agent-datagrip.hcl`.

Configure a DataGrip database connection with:
* `Address: 127.0.0.1:8101`
* `Token file: /opt/vault/vault-token-datagrip`

### Launching Vault Agent automatically

To skip launching the Vault Agent manually, you can configure your system manager to launch it on startup. For `systemd` create a `~/.config/systemd/user/vault-agent-datagrip.service` with:
```desktop
[Unit]
Description="Vault Agent to serve Tokens - DataGrip"

[Service]
SyslogIdentifier=vault-agent-datagrip
ExecStart=/usr/bin/vault agent -config=/opt/vault-agent-datagrip.hcl
Restart=always

[Install]
WantedBy=default.target
```

Enable the Vault system unit with `systemctl --user enable vault-agent-datagrip` and launch the Vault Agent with `systemctl --user start vault-agent-datagrip`.
