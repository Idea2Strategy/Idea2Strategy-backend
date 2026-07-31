rootProject.name = "idea2strategy-backend"

include(
    "apps:backend-api",
    "apps:backend-batch",
    "apps:backend-worker",
    "apps:admin-mcp",
    "modules:backend-domain",
    "modules:backend-application",
    "modules:backend-persistence",
    "modules:backend-messaging",
    "modules:backend-common",
    "db-migration",
)
