terraform {
  required_providers {
    postgresql = {
      source  = "cyrilgdn/postgresql"
      version = "1.26.0"
    }
    vault = {
      source  = "hashicorp/vault"
      version = "5.9.0"
    }
  }
}

locals {
  host = "postgres"
  port = 5432
  database = "root"
  username = "root"
  password = "rootpassword"
}

provider "postgresql" {
  host            = local.host
  port            = local.port
  database        = local.database
  username        = local.username
  password        = local.password
  sslmode         = "disable"
}

resource "postgresql_role" "ro" {
  name     = "ro"
}

resource "postgresql_grant" "readonly_tables" {
  database    = local.database
  role        = postgresql_role.ro.name
  schema      = "public"
  object_type = "table"
  privileges  = ["SELECT"]
}

resource "vault_database_secrets_mount" "db" {
  path = "database"

  postgresql {
    name              = "postgresql"
    username          = local.username
    password          = local.password
    connection_url = "postgresql://{{username}}:{{password}}@${local.host}:${local.port}/postgres?sslmode=disable"
    allowed_roles = [
      "readonly",
    ]
  }
}

resource "vault_database_secret_backend_role" "readonly" {
  name    = "readonly"
  backend = vault_database_secrets_mount.db.path
  db_name = vault_database_secrets_mount.db.postgresql[0].name
  creation_statements = [
    "CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' INHERIT;",
    "GRANT ${postgresql_role.ro.name} TO \"{{name}}\";",
  ]
  default_ttl = 1*60*60
  max_ttl = 24*60*60
}