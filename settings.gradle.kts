rootProject.name = "idea2strategy-backend"

include(
    "apps:backend-api",
    "apps:backend-batch",
    "apps:backend-worker",
    "apps:admin-mcp",
    "apps:idea2strategy-cli",
    "modules:backend-domain",
    "modules:backend-application",
    "modules:backend-persistence",
    "modules:backend-messaging",
    "modules:backend-common",
    "modules:backend-operator-trust",
    "db-migration",
)
