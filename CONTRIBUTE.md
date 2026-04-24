## Setup local environment
 - run `docker compose up -d`

### Access Vault
```
❯ export VAULT_ADDR='http://127.0.0.1:8201'
❯ export VAULT_TOKEN=root`
❯ vault read database/creds/readonly
Key                Value
---                -----
lease_id           database/creds/readonly/LkggASg8DHQBwzUf9Rn6xZB6
lease_duration     1h
lease_renewable    true
password           v-dWom3txhe2oLF7R82g
username           v-token-readonly-DQZ1pTA0O6GdcXuvipyo-1776990617
```

### Access Postgres
```
❯ export PGPASSWORD=v-dWom3txhe2oLF7R82g
❯ psql -h localhost -p 5434 -d root -U v-token-readonly-DQZ1pTA0O6GdcXuvipyo-1776990617
psql (18.3 (Ubuntu 18.3-1.pgdg24.04+1))
Type "help" for help.

v-token-readonly-DQZ1pTA0O6GdcXuvipyo-1776990617@localhost root=>
```
