#!/usr/bin/env bash
# InsightRAG one-command deploy for a fresh Ubuntu server (e.g. Oracle Cloud Always Free).
#
#   curl -fsSL https://raw.githubusercontent.com/gaurav-49/InsightRAG/main/deploy/install.sh | sudo bash
#
# What it does:
#   1. installs Docker (if missing) and adds swap on small machines
#   2. opens ports 80/443 in the server firewall
#   3. downloads (or updates) InsightRAG into /opt/insightrag
#   4. creates .env with generated secrets on first run; keeps it on later runs
#   5. builds and starts the production stack (HTTPS via Caddy)
#   6. prints the site URL and an admin access token
#
# Re-running it updates to the latest code and keeps data and secrets.
#
# Optional settings (pass as environment variables, e.g. `... | sudo DOMAIN=my.duckdns.org bash`):
#   DOMAIN             public hostname (default: <public-ip>.sslip.io)
#   LLM_PROVIDER       extractive (default) | anthropic | ollama
#   ANTHROPIC_API_KEY  needed when LLM_PROVIDER=anthropic
#   REPO_URL           git repository (default: https://github.com/gaurav-49/InsightRAG.git)
#   BRANCH             default: main
#   INSTALL_DIR        default: /opt/insightrag
#   SKIP_SYSTEM=1      skip Docker install, swap and firewall (for machines already set up)
#   PROJECT            Docker Compose project name (default: insightrag)

set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/gaurav-49/InsightRAG.git}"
BRANCH="${BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-/opt/insightrag}"
PROJECT="${PROJECT:-insightrag}"
COMPOSE=(docker compose -p "$PROJECT" -f docker-compose.yml -f docker-compose.prod.yml)

say() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------- system setup

if [[ "${SKIP_SYSTEM:-0}" != "1" ]]; then
  [[ "$(id -u)" -eq 0 ]] || die "run as root: curl -fsSL <url> | sudo bash"
  command -v apt-get >/dev/null || die "this installer supports Ubuntu/Debian servers"

  say "Installing prerequisites"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq git curl openssl python3 ca-certificates >/dev/null

  if ! command -v docker >/dev/null; then
    say "Installing Docker"
    curl -fsSL https://get.docker.com | sh >/dev/null
  fi
  systemctl enable --now docker >/dev/null 2>&1 || true
  if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
    usermod -aG docker "$SUDO_USER" || true
  fi

  # Building the Java API needs memory; add swap on machines with less than 4 GB RAM.
  mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
  if (( mem_kb < 4000000 )) && ! swapon --show | grep -q .; then
    say "Adding 4 GB swap (this machine has $((mem_kb / 1024)) MB RAM)"
    fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  fi

  say "Opening ports 80 and 443 in the server firewall"
  if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 80/tcp >/dev/null && ufw allow 443/tcp >/dev/null
  fi
  # Oracle Cloud Ubuntu images ship iptables rules that reject everything except SSH.
  if command -v iptables >/dev/null; then
    for port in 80 443; do
      iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null \
        || iptables -I INPUT 1 -p tcp --dport "$port" -m state --state NEW -j ACCEPT
    done
    command -v netfilter-persistent >/dev/null && netfilter-persistent save >/dev/null 2>&1 || true
  fi
fi

command -v docker >/dev/null || die "Docker is not installed"
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is required"

# ---------------------------------------------------------------------------- code

if [[ -d "$INSTALL_DIR/.git" ]]; then
  say "Updating InsightRAG in $INSTALL_DIR"
  git -C "$INSTALL_DIR" fetch -q origin "$BRANCH"
  git -C "$INSTALL_DIR" checkout -q "$BRANCH"
  git -C "$INSTALL_DIR" reset -q --hard "origin/$BRANCH"
else
  say "Downloading InsightRAG into $INSTALL_DIR"
  mkdir -p "$(dirname "$INSTALL_DIR")"
  git clone -q --branch "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi
cd "$INSTALL_DIR"

# ---------------------------------------------------------------------------- configuration

env_get() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2- || true; }
env_set() {
  if grep -qE "^$1=" .env; then
    python3 - "$1" "$2" <<'PY'
import sys
key, value = sys.argv[1], sys.argv[2]
lines = open(".env").read().splitlines()
open(".env", "w").write("\n".join(f"{key}={value}" if l.startswith(key + "=") else l for l in lines) + "\n")
PY
  else
    echo "$1=$2" >> .env
  fi
}

if [[ ! -f .env ]]; then
  say "Creating .env with generated secrets"
  cp deploy/.env.production.example .env
  env_set JWT_SECRET "$(openssl rand -hex 32)"
  env_set POSTGRES_PASSWORD "$(openssl rand -hex 24)"
  chmod 600 .env
else
  say "Keeping existing .env (secrets unchanged)"
fi

if [[ -n "${DOMAIN:-}" ]]; then
  env_set DOMAIN "$DOMAIN"
elif [[ -z "$(env_get DOMAIN)" || "$(env_get DOMAIN)" == CHANGE_ME* ]]; then
  ip=$(curl -4 -fsS --max-time 10 https://api.ipify.org || curl -4 -fsS --max-time 10 https://ifconfig.me || true)
  [[ -n "$ip" ]] || die "could not detect the public IP; re-run with DOMAIN=your.domain"
  env_set DOMAIN "${ip//./-}.sslip.io"
fi
[[ -n "${LLM_PROVIDER:-}" ]] && env_set LLM_PROVIDER "$LLM_PROVIDER"
[[ -n "${ANTHROPIC_API_KEY:-}" ]] && env_set ANTHROPIC_API_KEY "$ANTHROPIC_API_KEY"
if [[ "$(env_get LLM_PROVIDER)" == "anthropic" && -z "$(env_get ANTHROPIC_API_KEY)" ]]; then
  die "LLM_PROVIDER=anthropic needs ANTHROPIC_API_KEY"
fi
DOMAIN="$(env_get DOMAIN)"

# ---------------------------------------------------------------------------- start

say "Building and starting InsightRAG (first run takes 5-10 minutes)"
"${COMPOSE[@]}" up -d --build --remove-orphans --wait --wait-timeout 900

say "Checking the API"
status=$("${COMPOSE[@]}" exec -T api curl -fsS localhost:8080/api/v1/health 2>/dev/null \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' 2>/dev/null || echo "not responding")

token=$(python3 scripts/mint_token.py --scopes query admin --ttl 604800 --subject admin \
  --secret "$(env_get JWT_SECRET)")

cat <<EOF

────────────────────────────────────────────────────────────────────────
 InsightRAG is running.

   Site:   https://$DOMAIN
   API:    $status

 Admin access token (valid 7 days) - paste it into the console's
 "Access token" box:

$token

 New token any time:
   cd $INSTALL_DIR && python3 scripts/mint_token.py --scopes query admin --secret "\$(grep ^JWT_SECRET .env | cut -d= -f2-)"

 Update to the latest code: re-run this installer.
 Logs:  cd $INSTALL_DIR && docker compose -p $PROJECT logs -f api worker

 If the site does not open from your browser:
   * Oracle Cloud: allow TCP 80 and 443 in the subnet's Security List
     (Networking > Virtual Cloud Networks > Subnet > Security List > Add Ingress Rules)
   * the HTTPS certificate is issued on first visit and can take a minute
────────────────────────────────────────────────────────────────────────
EOF
