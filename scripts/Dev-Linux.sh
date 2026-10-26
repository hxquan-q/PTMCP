#!/usr/bin/env bash
# PTMCP local development bootstrap for a Linux server.
# Manages only the dedicated ptmcp-dev Compose project and local dev assets.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$ROOT/config/dev/compose.yaml"
SECRETS_DIR="$ROOT/config/dev/secrets"
LOCAL_DIR="$ROOT/.local/dev"
RUN_DIR="$LOCAL_DIR/run"
ENV_FILE="$LOCAL_DIR/dev.env"
MAVEN_SETTINGS="$LOCAL_DIR/maven-settings.xml"
MIGRATION_DIR="$ROOT/ptmcp-parent/ptmcp-persistence/src/main/resources/db/ptmcp-migration"
DEV_IDENTITY_SQL="$SCRIPT_DIR/dev_identity.sql"
SEED_ADMIN_PY="$SCRIPT_DIR/dev_seed_admin.py"
ADMIN_CREDENTIALS="$LOCAL_DIR/admin-credentials.txt"
COMPOSE_PROJECT="ptmcp-dev"
MYSQL_CONTAINER="ptmcp-mysql"
JAVA21_HOME=""

info() { printf '[dev] %s\n' "$*"; }
warn() { printf '[dev] WARN: %s\n' "$*" >&2; }
die() {
    printf '[dev] ERROR: %s\n' "$*" >&2
    exit 1
}

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

require_infra_tools() {
    require_cmd docker
    docker compose version >/dev/null 2>&1 || die "docker compose is required"
    require_cmd python3
    require_cmd openssl
    require_cmd curl
    require_cmd sha256sum
}

detect_java_home() {
    local candidate version
    for candidate in "${JAVA_HOME:-}" /usr/lib/jvm/java-21-openjdk-amd64 "$HOME"/.jdks/jdk-21*; do
        [ -n "$candidate" ] || continue
        [ -x "$candidate/bin/java" ] || continue
        version="$("$candidate/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
        if [[ "$version" == 21.* ]]; then
            JAVA21_HOME="$candidate"
            return 0
        fi
    done
    die "JDK 21 not found. Install it with: sudo apt-get install -y openjdk-21-jdk-headless"
}

ensure_dirs() {
    umask 077
    mkdir -p "$SECRETS_DIR" "$LOCAL_DIR" "$RUN_DIR" "$LOCAL_DIR/wal"
    chmod 700 "$SECRETS_DIR" "$LOCAL_DIR"
}

ensure_maven_settings() {
    ensure_dirs
    if [ ! -s "$MAVEN_SETTINGS" ]; then
        cat >"$MAVEN_SETTINGS" <<'EOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <mirrors>
    <mirror>
      <id>central</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF
        chmod 600 "$MAVEN_SETTINGS"
    fi
}

run_maven() {
    if command -v mvn >/dev/null 2>&1; then
        mvn -s "$MAVEN_SETTINGS" "$@"
    else
        sh ./mvnw -s "$MAVEN_SETTINGS" "$@"
    fi
}

write_secret_file() {
    local path="$1"
    shift
    if [ ! -s "$path" ]; then
        printf '%s\n' "$*" >"$path"
        chmod 600 "$path"
    fi
}

mysql_volume_exists() {
    docker volume inspect "${COMPOSE_PROJECT}_mysql_data" >/dev/null 2>&1
}

ensure_secrets() {
    ensure_dirs
    local volume_exists=0
    if mysql_volume_exists; then
        volume_exists=1
    fi
    if [ "$volume_exists" -eq 1 ]; then
        local required
        for required in mysql-root.txt mysql-app.txt mysql-client.cnf; do
            [ -s "$SECRETS_DIR/$required" ] ||
                die "MySQL data volume exists but $SECRETS_DIR/$required is missing. Restore it or run: $0 reset --yes"
        done
    fi

    write_secret_file "$SECRETS_DIR/mysql-root.txt" "$(openssl rand -hex 24)"
    write_secret_file "$SECRETS_DIR/mysql-app.txt" "$(openssl rand -hex 24)"
    write_secret_file "$SECRETS_DIR/postgres-root.txt" "$(openssl rand -hex 24)"
    write_secret_file "$SECRETS_DIR/postgres-app.txt" "$(openssl rand -hex 24)"
    write_secret_file "$SECRETS_DIR/redis-password.txt" "$(openssl rand -hex 24)"

    if [ ! -s "$SECRETS_DIR/mysql-client.cnf" ]; then
        {
            printf '[client]\n'
            printf 'user=root\n'
            printf 'password=%s\n' "$(cat "$SECRETS_DIR/mysql-root.txt")"
        } >"$SECRETS_DIR/mysql-client.cnf"
        chmod 600 "$SECRETS_DIR/mysql-client.cnf"
    fi

    if [ ! -s "$SECRETS_DIR/postgres-init.sql" ]; then
        {
            printf '%s\n' '-- Local development only. Do not use in production.'
            printf "CREATE ROLE ptmcp_dev LOGIN PASSWORD '%s';\n" "$(cat "$SECRETS_DIR/postgres-app.txt")"
            printf '%s\n' 'GRANT ALL PRIVILEGES ON DATABASE ptmcp_dev TO ptmcp_dev;'
            printf '%s\n' '\connect ptmcp_dev'
            printf '%s\n' 'GRANT ALL ON SCHEMA public TO ptmcp_dev;'
        } >"$SECRETS_DIR/postgres-init.sql"
        chmod 600 "$SECRETS_DIR/postgres-init.sql"
    fi

    if [ ! -s "$SECRETS_DIR/redis.conf" ]; then
        {
            printf '%s\n' 'bind 0.0.0.0'
            printf '%s\n' 'protected-mode yes'
            printf 'requirepass %s\n' "$(cat "$SECRETS_DIR/redis-password.txt")"
            printf '%s\n' 'appendonly yes'
            printf '%s\n' 'dir /data'
        } >"$SECRETS_DIR/redis.conf"
        chmod 600 "$SECRETS_DIR/redis.conf"
    fi
}

ensure_env() {
    ensure_dirs
    local mysql_app
    mysql_app="$(cat "$SECRETS_DIR/mysql-app.txt")"

    if [ -s "$ENV_FILE" ]; then
        load_env
        [ "${PTMCP_ADMIN_DATASOURCE_PASSWORD:-}" = "$mysql_app" ] ||
            die "dev.env does not match config/dev/secrets/mysql-app.txt. Restore it or run: $0 reset --yes"
        [ -n "${PTMCP_ADMIN_CREDENTIALKEY:-}" ] || die "dev.env is missing PTMCP_ADMIN_CREDENTIALKEY"
        return 0
    fi

    if [ ! -s "$LOCAL_DIR/oauth-signing.pem" ]; then
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
            -out "$LOCAL_DIR/oauth-signing.pem" >/dev/null 2>&1
        chmod 600 "$LOCAL_DIR/oauth-signing.pem"
    fi

    local admin_key gateway_key wal_key
    admin_key="$(openssl rand -base64 32 | tr -d '\n')"
    gateway_key="$(openssl rand -base64 32 | tr -d '\n')"
    wal_key="$(openssl rand -base64 32 | tr -d '\n')"

    cat >"$ENV_FILE" <<EOF
# Generated local development configuration. Do not commit.
PTMCP_DEV_HOME='$LOCAL_DIR'
PTMCP_UI_PUBLIC='false'
PTMCP_ADMIN_DATASOURCE_URL='jdbc:mysql://127.0.0.1:13306/ptmcp_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
PTMCP_ADMIN_DATASOURCE_USERNAME='ptmcp_dev'
PTMCP_ADMIN_DATASOURCE_PASSWORD='$mysql_app'
PTMCP_ADMIN_COOKIESECURE='false'
PTMCP_ADMIN_CREDENTIALKEY='$admin_key'
PTMCP_ADMIN_DATASOURCE_ALLOWEDCIDRS='127.0.0.1/32,::1/128'
PTMCP_MCP_URL='http://127.0.0.1:8082/mcp'

PTMCP_AUTHZ_DB_URL='jdbc:mysql://127.0.0.1:13306/ptmcp_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
PTMCP_AUTHZ_DB_USERNAME='ptmcp_dev'
PTMCP_AUTHZ_DB_PASSWORD='$mysql_app'
PTMCP_OAUTH_ENABLED='true'
PTMCP_OAUTH_ISSUER='http://127.0.0.1:8081'
PTMCP_OAUTH_AUDIENCE='ptmcp-mcp'
PTMCP_OAUTH_SIGNING_KEY_FILE='$LOCAL_DIR/oauth-signing.pem'
PTMCP_AS_COOKIE_SECURE='false'

# Gateway MCP stays disabled until local TLS trust material and an OAuth
# client are configured. See docs/ops/development-linux-server.md.
PTMCP_GATEWAY_ENABLED='false'
PTMCP_GATEWAY_METADATAURL='jdbc:mysql://127.0.0.1:13306/ptmcp_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8'
PTMCP_GATEWAY_METADATAUSERNAME='ptmcp_dev'
PTMCP_GATEWAY_METADATAPASSWORD='$mysql_app'
PTMCP_GATEWAY_CREDENTIALKEY='$gateway_key'
PTMCP_GATEWAY_ALLOWEDCIDRS='127.0.0.1/32,::1/128'
PTMCP_GATEWAY_WALDIRECTORY='$LOCAL_DIR/wal'
PTMCP_GATEWAY_WALKEY='$wal_key'
PTMCP_GATEWAY_ISSUER='http://127.0.0.1:8081'
PTMCP_GATEWAY_RESOURCE='ptmcp-mcp'
PTMCP_GATEWAY_JWKSURI='http://127.0.0.1:8081/oauth2/jwks'
PTMCP_GATEWAY_RESOURCEMETADATAURI='http://127.0.0.1:8082/.well-known/oauth-protected-resource'
PTMCP_GATEWAY_TRUSTSTOREPATH=''
PTMCP_GATEWAY_TRUSTSTOREPASSWORD=''
PTMCP_GATEWAY_PGSSLROOTCERT=''

# Optional disposable-local override; default is the pinned MySQL 8.4 image.
# PTMCP_MYSQL_IMAGE='mysql:8.0'
EOF
    chmod 600 "$ENV_FILE"
    load_env
}

load_env() {
    [ -s "$ENV_FILE" ] || die "missing $ENV_FILE; run: $0 up"
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
}

check_port() {
    local port="$1"
    command -v ss >/dev/null 2>&1 || return 0
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}$"; then
        if ! docker ps --format '{{.Names}} {{.Ports}}' | grep -q "ptmcp-.*:${port}->"; then
            die "port ${port} is already in use; stop the other process first"
        fi
    fi
}

start_infra() {
    require_infra_tools
    ensure_secrets
    ensure_env

    local services=(mysql)
    local arg
    for arg in "$@"; do
        case "$arg" in
            --with-pg) services+=(postgres) ;;
            --with-redis) services+=(redis) ;;
            --all) services=(mysql postgres redis) ;;
            *) die "unknown up option: $arg" ;;
        esac
    done

    check_port 13306
    for arg in "${services[@]}"; do
        if [ "$arg" = "postgres" ]; then
            check_port 15432
        fi
        if [ "$arg" = "redis" ]; then
            check_port 16379
        fi
    done

    info "starting infrastructure: ${services[*]}"
    compose up -d --wait --wait-timeout 300 "${services[@]}"
}

container_health() {
    docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || true
}

ensure_mysql() {
    local health
    health="$(container_health "$MYSQL_CONTAINER")"
    [ "$health" = "healthy" ] ||
        die "MySQL is not healthy (state: ${health:-missing}); run: $0 up"
}

mysql_exec() {
    compose exec -T -u root mysql sh -c \
        'MYSQL_PWD="$(cat /run/secrets/mysql_root)" exec mysql --default-character-set=utf8mb4 -uroot ptmcp_dev'
}

mysql_query() {
    compose exec -T -u root mysql sh -c \
        'MYSQL_PWD="$(cat /run/secrets/mysql_root)" exec mysql -N -B -uroot ptmcp_dev'
}

migrate() {
    ensure_mysql
    local first
    first="$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -print -quit)"
    [ -n "$first" ] || die "no migration files found in $MIGRATION_DIR"

    info "ensuring dev migration ledger"
    mysql_query <<'SQL'
CREATE TABLE IF NOT EXISTS ptmcp_dev_schema_migration (
    version VARCHAR(20) NOT NULL,
    checksum CHAR(64) NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_bin;
SQL

    local name file version checksum existing
    while IFS= read -r name; do
        file="$MIGRATION_DIR/$name"
        version="${name%%__*}"
        checksum="$(sha256sum "$file" | awk '{print $1}')"
        existing="$(printf "SELECT checksum FROM ptmcp_dev_schema_migration WHERE version='%s';\n" "$version" | mysql_query | tr -d '[:space:]')"
        if [ -n "$existing" ]; then
            [ "$existing" = "$checksum" ] ||
                die "migration $version checksum changed. Run: $0 reset --yes"
            info "skip $version (already applied)"
            continue
        fi
        info "apply $version"
        mysql_exec <"$file" || die "migration $version failed. Fix the cause or run: $0 reset --yes"
        printf "INSERT INTO ptmcp_dev_schema_migration (version, checksum, applied_at) VALUES ('%s', '%s', UTC_TIMESTAMP(6));\n" \
            "$version" "$checksum" | mysql_exec
    done < <(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -printf '%f\n' | sort -V)
}

seed() {
    ensure_mysql
    info "ensuring development identity table"
    mysql_exec <"$DEV_IDENTITY_SQL"

    local count
    count="$(printf "SELECT COUNT(*) FROM sys_user_login_name WHERE normalized_name='admin';\n" | mysql_query | tr -d '[:space:]')"
    if [ "$count" = "0" ]; then
        info "creating local administrator with a random password"
        python3 "$SEED_ADMIN_PY" --root "$ROOT" --output "$LOCAL_DIR/seed-admin.sql" \
            --credentials "$ADMIN_CREDENTIALS"
        mysql_exec <"$LOCAL_DIR/seed-admin.sql"
        rm -f "$LOCAL_DIR/seed-admin.sql"
    else
        info "local administrator already exists; password unchanged"
    fi

    mysql_exec <"$DEV_IDENTITY_SQL"
    if [ -s "$ADMIN_CREDENTIALS" ]; then
        info "admin credentials: $ADMIN_CREDENTIALS"
    else
        warn "admin exists but $ADMIN_CREDENTIALS is missing; run: $0 reset --yes"
    fi
}

up() {
    start_infra "$@"
    migrate
    seed
    status
    info "next: $0 build && $0 start"
}

status() {
    echo "== containers"
    compose ps || true
    echo "== health"
    local entry name port
    for entry in "admin 8080" "authz 8081" "gateway 8082"; do
        read -r name port <<<"$entry"
        if curl -fsS --max-time 2 "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1; then
            echo "${name}: UP (http://127.0.0.1:${port})"
        else
            echo "${name}: down"
        fi
    done
    if curl -fsS --max-time 2 "http://127.0.0.1:5173/" >/dev/null 2>&1; then
        echo "ui: UP (http://127.0.0.1:5173)"
    else
        echo "ui: down"
    fi
    echo "== local files"
    echo "env: $ENV_FILE"
    echo "admin credentials: $ADMIN_CREDENTIALS"
}

build() {
    detect_java_home
    ensure_maven_settings
    info "building backend with JDK 21 (tests skipped)"
    (
        cd "$ROOT/ptmcp-parent"
        JAVA_HOME="$JAVA21_HOME" PATH="$JAVA21_HOME/bin:$PATH" \
            run_maven -B -ntp -DskipTests install
    )
}

run_backend() {
    local module="$1"
    detect_java_home
    ensure_maven_settings
    load_env
    [ -f "$ROOT/ptmcp-parent/$module/pom.xml" ] || die "unknown backend module: $module"
    if [ ! -d "$ROOT/ptmcp-parent/$module/target/classes" ]; then
        warn "$module has not been built; run: $0 build"
    fi
    cd "$ROOT/ptmcp-parent"
    export JAVA_HOME="$JAVA21_HOME"
    export PATH="$JAVA21_HOME/bin:$PATH"
    run_maven -pl "$module" spring-boot:run
}

run_gateway() {
    load_env
    if [ "${PTMCP_GATEWAY_ENABLED:-false}" != "true" ]; then
        warn "PTMCP_GATEWAY_ENABLED is false; the gateway starts with MCP tools disabled"
    fi
    run_backend ptmcp-gateway
}

run_ui() {
    require_cmd node
    require_cmd pnpm
    load_env
    cd "$ROOT/ptmcp-ui"
    if [ ! -d node_modules ]; then
        info "installing frontend dependencies"
        pnpm install --frozen-lockfile --ignore-scripts
    fi
    if [ "${PTMCP_UI_PUBLIC:-false}" = "true" ]; then
        exec pnpm dev:public
    fi
    exec pnpm dev
}

write_launcher() {
    local name="$1"
    local launcher="$RUN_DIR/$name.sh"
    case "$name" in
        admin | authz | gateway)
            ensure_maven_settings
            cat >"$launcher" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
set -a
. "$ENV_FILE"
set +a
export JAVA_HOME="$JAVA21_HOME"
export PATH="$JAVA21_HOME/bin:\$PATH"
cd "$ROOT/ptmcp-parent"
if command -v mvn >/dev/null 2>&1; then
    exec mvn -s "$MAVEN_SETTINGS" -pl ptmcp-$name spring-boot:run
else
    exec sh ./mvnw -s "$MAVEN_SETTINGS" -pl ptmcp-$name spring-boot:run
fi
EOF
            ;;
        ui)
            cat >"$launcher" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
set -a
. "$ENV_FILE"
set +a
cd "$ROOT/ptmcp-ui"
if [ ! -d node_modules ]; then
    pnpm install --frozen-lockfile --ignore-scripts
fi
if [ "\${PTMCP_UI_PUBLIC:-false}" = "true" ]; then
    exec pnpm dev:public
fi
exec pnpm dev
EOF
            ;;
        *) die "unknown service: $name" ;;
    esac
    chmod 700 "$launcher"
}

start_one() {
    local name="$1"
    local pidfile="$RUN_DIR/$name.pid"
    write_launcher "$name"
    if [ -f "$pidfile" ]; then
        local old
        old="$(cat "$pidfile")"
        if kill -0 "$old" 2>/dev/null; then
            warn "$name already running (pid $old)"
            return 0
        fi
        rm -f "$pidfile"
    fi
    if command -v setsid >/dev/null 2>&1; then
        setsid "$RUN_DIR/$name.sh" >"$RUN_DIR/$name.log" 2>&1 </dev/null &
    else
        nohup "$RUN_DIR/$name.sh" >"$RUN_DIR/$name.log" 2>&1 </dev/null &
    fi
    echo "$!" >"$pidfile"
    info "started $name (pid $(cat "$pidfile"), log $RUN_DIR/$name.log)"
}

stop_one() {
    local name="$1"
    local pidfile="$RUN_DIR/$name.pid"
    [ -f "$pidfile" ] || return 0
    local pid
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
        kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
        local _
        for _ in {1..20}; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.5
        done
    fi
    rm -f "$pidfile"
    info "stopped $name"
}

wait_health() {
    local name="$1" url="$2" tries="${3:-300}" i
    for ((i = 1; i <= tries; i++)); do
        if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
            info "$name is ready"
            return 0
        fi
        sleep 1
    done
    warn "$name did not become ready; see $RUN_DIR/$name.log"
    return 1
}

start() {
    up
    build
    detect_java_home
    load_env

    if [ ! -d "$ROOT/ptmcp-ui/node_modules" ]; then
        info "installing frontend dependencies before start"
        (cd "$ROOT/ptmcp-ui" && pnpm install --frozen-lockfile --ignore-scripts)
    fi

    start_one admin
    start_one authz
    if [ "${PTMCP_GATEWAY_ENABLED:-false}" = "true" ]; then
        start_one gateway
    fi
    start_one ui

    wait_health admin "http://127.0.0.1:8080/actuator/health" || true
    wait_health authz "http://127.0.0.1:8081/actuator/health" || true
    if [ "${PTMCP_GATEWAY_ENABLED:-false}" = "true" ]; then
        wait_health gateway "http://127.0.0.1:8082/actuator/health" || true
    fi
    wait_health ui "http://127.0.0.1:5173/" || true
    status
}

stop() {
    stop_one ui
    stop_one gateway
    stop_one authz
    stop_one admin
}

down() {
    stop
    compose down
}

reset() {
    local confirm="${1:-}"
    if [ "$confirm" != "--yes" ]; then
        printf 'This removes the dedicated ptmcp-dev containers and database volumes. Type yes: '
        local answer
        read -r answer
        [ "$answer" = "yes" ] || {
            info "aborted"
            return 0
        }
    fi
    stop
    compose down -v --remove-orphans
    rm -f "$ADMIN_CREDENTIALS" "$LOCAL_DIR/seed-admin.sql"
    info "reset complete; run: $0 up"
}

logs() {
    case "${1:-}" in
        infra) compose logs -f ;;
        admin | authz | gateway | ui)
            [ -f "$RUN_DIR/$1.log" ] || die "no log for $1; run: $0 start"
            tail -n 100 -f "$RUN_DIR/$1.log"
            ;;
        *) die "usage: $0 logs infra|admin|authz|gateway|ui" ;;
    esac
}

doctor() {
    require_infra_tools
    detect_java_home
    require_cmd node
    require_cmd pnpm
    echo "java:    $("$JAVA21_HOME/bin/java" -version 2>&1 | head -1)"
    echo "node:    $(node -v)"
    echo "pnpm:    $(pnpm -v)"
    echo "docker:  $(docker --version)"
    echo "compose: $(docker compose version)"
    if command -v mvn >/dev/null 2>&1; then
        echo "maven:   $(mvn -version 2>/dev/null | head -1)"
    else
        echo "maven:   wrapper (mvn not on PATH)"
    fi
    echo "root:    $ROOT"
}

env_paths() {
    echo "env: $ENV_FILE"
    echo "admin credentials: $ADMIN_CREDENTIALS"
    echo "secrets: $SECRETS_DIR"
}

usage() {
    cat <<'EOF'
PTMCP Linux local development

Usage: ./scripts/Dev-Linux.sh <command>

Commands:
  up [--with-pg] [--with-redis] [--all]
      Generate local secrets, start MySQL 8.4, apply V1-V6, create admin.
  status            Show container and service health.
  doctor            Check JDK 21, Node, pnpm, Docker and Compose.
  migrate           Apply pending metadata migrations.
  seed              Create the local admin if it does not exist.
  build             Build all backend modules with tests skipped.
  start             up + build + run admin, authz and UI in background.
  stop              Stop background admin/authz/gateway/UI processes.
  down              stop + remove containers, keep database volumes.
  reset [--yes]     Remove containers and database volumes, then re-init.
  admin             Run ptmcp-admin in the foreground.
  authz             Run ptmcp-authz in the foreground.
  gateway           Run ptmcp-gateway in the foreground (MCP disabled by default).
  ui                Install frontend dependencies if needed and run Vite.
  logs <name>       Follow infra|admin|authz|gateway|ui logs.
  env               Print local config/credential file paths, not contents.
  help              Show this help.

Typical first run:
  ./scripts/Dev-Linux.sh doctor
  ./scripts/Dev-Linux.sh up
  ./scripts/Dev-Linux.sh build
  ./scripts/Dev-Linux.sh start

Open http://127.0.0.1:5173 and use the credentials in
.local/dev/admin-credentials.txt. The admin password is random and never
printed by this script.
EOF
}

main() {
    case "${1:-help}" in
        up)
            shift
            up "$@"
            ;;
        status) status ;;
        doctor) doctor ;;
        migrate) migrate ;;
        seed) seed ;;
        build) build ;;
        start) start ;;
        stop) stop ;;
        down) down ;;
        reset)
            shift
            reset "${1:-}"
            ;;
        admin) run_backend ptmcp-admin ;;
        authz) run_backend ptmcp-authz ;;
        gateway) run_gateway ;;
        ui) run_ui ;;
        logs)
            shift
            logs "${1:-}"
            ;;
        env) env_paths ;;
        help | -h | --help) usage ;;
        *)
            usage
            exit 2
            ;;
    esac
}

main "$@"
