#!/usr/bin/env bash
# MDMesh quick start — deploy from PUBLISHED images, no clone and no build. Needs only Docker.
# Run from anywhere (the `bash <(...)` form keeps the prompts interactive):
#
#   bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/main/quickstart.sh)
#
# It creates ./mdmesh, downloads the pull-only compose + seed, generates secrets, brings the stack
# up, and prints the console URL + a temporary admin password (you set your own on first login).
set -euo pipefail

REPO="MDMesh-app/MDMesh"
BRANCH="main"
RAW="https://raw.githubusercontent.com/${REPO}/${BRANCH}"
IMAGE_OWNER_DEFAULT="mdmesh-app"

say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
rand() { openssl rand -hex 24; }

command -v docker >/dev/null || { err "Docker is required."; exit 1; }
docker compose version >/dev/null 2>&1 || { err "Docker Compose v2 is required ('docker compose')."; exit 1; }
command -v curl    >/dev/null || { err "curl is required."; exit 1; }
command -v openssl >/dev/null || { err "openssl is required."; exit 1; }

DIR="${MDMESH_DIR:-mdmesh}"
mkdir -p "$DIR" && cd "$DIR"
[ -f .env ] && { err "An .env already exists in $(pwd) — refusing to overwrite. Remove it to re-run."; exit 1; }

say "== MDMesh quick start (published images) =="
echo "Installing into: $(pwd)"
echo
echo "Hosting mode:"
echo "  1) Cloudflare Tunnel   (no open ports; Cloudflare manages TLS — needs a domain in Cloudflare)"
echo "  2) Your own domain     (open 80/443; Caddy auto-provisions a Let's Encrypt cert)"
echo "  3) Host Nginx          (use existing Nginx on host; reverse-proxies to Docker backend)"
MODE=""
while [ "$MODE" != "1" ] && [ "$MODE" != "2" ] && [ "$MODE" != "3" ]; do
  read -rp "Choose [1/2/3]: " MODE || { err "No selection (non-interactive run?). Aborting."; exit 1; }
  case "$MODE" in 1|2|3) ;; *) warn "Please enter 1, 2 or 3." ;; esac
done

if [ "$MODE" = "3" ]; then
  command -v nginx >/dev/null || { err "Nginx command not found on host! Aborting installation for Host Nginx mode."; exit 1; }
fi

read -rp "Pull releases from GitHub repo [${REPO}]: " GH_REPO;       GH_REPO="${GH_REPO:-$REPO}"
read -rp "Image owner (GHCR, lowercase) [${IMAGE_OWNER_DEFAULT}]: " IMAGE_OWNER; IMAGE_OWNER="${IMAGE_OWNER:-$IMAGE_OWNER_DEFAULT}"

DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)

SERVER_PORT="8080"
SUPERVISOR_PORT="9000"
HOST_NGINX=0

if [ "$MODE" = "1" ]; then
  read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST
  read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN
  BASE_URL="https://${HOST}"; SITE_ADDRESS=":80"; ACME_EMAIL=""
  COMPOSE_FILE="docker-compose.yml"; COMPOSE_PROFILES="cloudflare"
  EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
elif [ "$MODE" = "2" ]; then
  read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST
  read -rp "Email for Let's Encrypt: " ACME_EMAIL
  BASE_URL="https://${HOST}"; SITE_ADDRESS="${HOST}"; TUNNEL_TOKEN=""
  COMPOSE_FILE="docker-compose.yml:docker-compose.domain.yml"; COMPOSE_PROFILES=""
  EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
else
  read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST
  read -rp "Backend Server port [8080]: " INP_SERVER_PORT
  read -rp "Backend Supervisor port [9000]: " INP_SUPERVISOR_PORT
  SERVER_PORT="${INP_SERVER_PORT:-8080}"
  SUPERVISOR_PORT="${INP_SUPERVISOR_PORT:-9000}"
  HOST_NGINX=1
  BASE_URL="https://${HOST}"; SITE_ADDRESS="${HOST}"; ACME_EMAIL=""; TUNNEL_TOKEN=""
  COMPOSE_FILE="docker-compose.yml:docker-compose.nginx.yml"; COMPOSE_PROFILES="none"
  EXTRA_NOTE="Nginx configured at /etc/nginx/sites-available/mdmesh.conf."
fi

say "Downloading the pull-only compose + seed…"
curl -fsSL "${RAW}/docker-compose.release.yml" -o docker-compose.yml
curl -fsSL "${RAW}/docker-compose.domain.yml"  -o docker-compose.domain.yml
curl -fsSL "${RAW}/docker-compose.nginx.yml"   -o docker-compose.nginx.yml
mkdir -p docker
curl -fsSL "${RAW}/docker/nginx.conf.template" -o docker/nginx.conf.template
mkdir -p install/sql
curl -fsSL "${RAW}/install/sql/hmdm_init.en.sql" -o install/sql/hmdm_init.en.sql
curl -fsSL "${RAW}/install/sql/post_seed.sql"    -o install/sql/post_seed.sql
mkdir -p install/lib
curl -fsSL "${RAW}/install/lib/db.sh"             -o install/lib/db.sh
# Shared seed rules with setup.sh / the native installer (seed gate, verified seed, post-seed repairs).
# shellcheck source=install/lib/db.sh
. ./install/lib/db.sh

cat > .env <<EOF
DB_NAME=mdmesh
DB_USER=mdmesh
DB_PASSWORD=${DB_PASSWORD}
BASE_URL=${BASE_URL}
HASH_SECRET=${HASH_SECRET}
SECURE_ENROLLMENT=0
SITE_ADDRESS=${SITE_ADDRESS}
ACME_EMAIL=${ACME_EMAIL}
TUNNEL_TOKEN=${TUNNEL_TOKEN}
IMAGE_OWNER=${IMAGE_OWNER}
SERVER_VERSION=latest
WEB_VERSION=latest
SUPERVISOR_VERSION=latest
GITHUB_REPO=${GH_REPO}
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
CURRENT_VERSION=0.0.0
GITHUB_TOKEN=
AUTO_UPDATE=0
COMPOSE_PROJECT_NAME=mdmesh
COMPOSE_FILE=${COMPOSE_FILE}
COMPOSE_PROFILES=${COMPOSE_PROFILES}
SERVER_PORT=${SERVER_PORT}
SUPERVISOR_PORT=${SUPERVISOR_PORT}
HOST_NGINX=${HOST_NGINX}
SMTP_HOST=
SMTP_PORT=25
SMTP_FROM=mdm@${HOST}
EOF
chmod 600 .env
say "Wrote .env (secrets generated). docker compose reads COMPOSE_FILE/PROFILES from it."

say "Pulling images…"
docker compose pull || { err "Could not pull images from ghcr.io/${IMAGE_OWNER}. Has a release been published? (cut one with: git tag v0.1.0 && git push --tags)"; exit 1; }
say "Starting the stack…"
docker compose up -d

say "Waiting for the server to finish first-boot (Liquibase)…"
BOOTED=0
for _ in $(seq 1 60); do
  if docker compose exec -T server test -f /opt/mdmesh/initialized.txt 2>/dev/null; then BOOTED=1; break; fi
  sleep 5
done
if [ "$BOOTED" != 1 ]; then
  err "Server did not finish first-boot within ~5 minutes. Last server logs:"
  docker compose logs --tail 40 server 2>&1 || true
  err "Fix the issue above and re-run the quick start from this directory ($(pwd))."; exit 1
fi

# Same rules as setup.sh (shared install/lib/db.sh): seed only a fresh database, verify the seed, then
# the always-run repairs that switch on QR/token enrollment (this step used to be missing here).
# shellcheck disable=SC2034  # PSQL is consumed by install/lib/db.sh
PSQL=(docker compose exec -T postgres psql -U mdmesh -d mdmesh)
STATE=$(mdm_db_state)
case "$STATE" in
  fresh)  ;;
  seeded) err "This database is already seeded — the quick start is for new installs only. To upgrade, use ./setup.sh in a clone."; exit 1 ;;
  *)      err "Could not confirm a fresh database (state: ${STATE}). Aborting before touching data."; exit 1 ;;
esac
say "Seeding settings + admin…"
if ! mdm_seed "admin@${HOST}" install/sql/hmdm_init.en.sql "$ADMIN_PASSWORD" "$RESET_TOKEN"; then
  err "Seeding failed — the install is NOT usable yet. Fix the error above and re-run."; exit 1
fi
if ! mdm_post_seed install/sql/post_seed.sql; then
  err "Post-seed repairs failed — device enrollment would not work. Fix the error above and re-run."; exit 1
fi

# Configure host Nginx if in Host Nginx mode
if [ "${HOST_NGINX:-0}" = "1" ]; then
  say "Setting up Host Nginx web assets and configuration…"
  WEB_DIR="$(pwd)/web_dist"
  mkdir -p "$WEB_DIR"

  # Fetch or extract web files
  WEB_ZIP_URL=""
  if [ -n "${GH_REPO:-}" ] && command -v python3 >/dev/null && command -v curl >/dev/null; then
    REL=$(curl -fsSL "https://api.github.com/repos/${GH_REPO}/releases/latest" 2>/dev/null || true)
    WEB_ZIP_URL=$(printf '%s' "$REL" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(next((a["browser_download_url"] for a in d.get("assets",[]) if a["name"]=="mdmesh-web.zip"),""))' 2>/dev/null || true)
  fi

  EXTRACTED=0
  if [ -n "$WEB_ZIP_URL" ]; then
    TMP_ZIP=$(mktemp)
    if curl -fsSL "$WEB_ZIP_URL" -o "$TMP_ZIP" 2>/dev/null; then
      if command -v unzip >/dev/null; then
        unzip -q -o "$TMP_ZIP" -d "$WEB_DIR" && EXTRACTED=1
      elif command -v python3 >/dev/null; then
        python3 -c "import zipfile; zipfile.ZipFile('$TMP_ZIP').extractall('$WEB_DIR')" && EXTRACTED=1
      fi
    fi
    rm -f "$TMP_ZIP"
  fi

  if [ "$EXTRACTED" != 1 ]; then
    say "Extracting web static assets from mdmesh-web container image…"
    WEB_IMAGE="ghcr.io/${IMAGE_OWNER:-mdmesh-app}/mdmesh-web:${WEB_VERSION:-latest}"
    CID=$(docker create "$WEB_IMAGE" 2>/dev/null || true)
    if [ -n "$CID" ]; then
      docker cp "$CID:/srv/." "$WEB_DIR" 2>/dev/null || true
      docker rm "$CID" >/dev/null 2>&1 || true
    fi
  fi

  # Certbot check
  if command -v certbot >/dev/null; then
    say "Running certbot to configure SSL/TLS..."
    certbot -d "$HOST" || warn "Certbot failed or was cancelled. Please configure HTTPS manually."
  else
    warn "certbot was not found. Please configure SSL/TLS certificates for ${HOST} in Nginx manually."
  fi

  # Generate Nginx configuration
  NGINX_CONF_AVAILABLE="/etc/nginx/sites-available/mdmesh.conf"
  NGINX_CONF_ENABLED="/etc/nginx/sites-enabled/mdmesh.conf"

  TMP_NGINX_CONF=$(mktemp)
  sed -e "s#__HOST__#${HOST}#g" \
      -e "s#__WEB_ROOT__#${WEB_DIR}#g" \
      -e "s#__SERVER_PORT__#${SERVER_PORT:-8080}#g" \
      -e "s#__SUPERVISOR_PORT__#${SUPERVISOR_PORT:-9000}#g" \
      docker/nginx.conf.template > "$TMP_NGINX_CONF"

  if [ -w "/etc/nginx/sites-available" ] || [ "$EUID" -eq 0 ]; then
    mkdir -p /etc/nginx/sites-available /etc/nginx/sites-enabled
    cp "$TMP_NGINX_CONF" "$NGINX_CONF_AVAILABLE"
    ln -sf "$NGINX_CONF_AVAILABLE" "$NGINX_CONF_ENABLED"
    rm -f "$TMP_NGINX_CONF"
    if command -v nginx >/dev/null; then
      nginx -t && (systemctl reload nginx 2>/dev/null || nginx -s reload 2>/dev/null || true)
    fi
  else
    say "Root permissions required to write /etc/nginx/sites-available/mdmesh.conf."
    say "Writing generated configuration to ./mdmesh.nginx.conf..."
    cp "$TMP_NGINX_CONF" ./mdmesh.nginx.conf
    rm -f "$TMP_NGINX_CONF"
    warn "Please copy ./mdmesh.nginx.conf to /etc/nginx/sites-available/mdmesh.conf, link to sites-enabled, and reload Nginx."
  fi

fi

echo
say "== MDMesh is up =="
echo "  Console:        ${BASE_URL}"
echo "  REST API base:  ${BASE_URL}/rest"
echo "  Login:          admin"
echo "  Password:       ${ADMIN_PASSWORD}   (temporary — you'll set your own on first login)"
echo "  Directory:      $(pwd)   (run 'docker compose' commands from here)"
echo
warn "Save that password now — it is not stored anywhere in clear text."
echo "Next: $EXTRA_NOTE"
echo "Enroll devices from the console's Enroll page (it builds the QR with ${BASE_URL})."
