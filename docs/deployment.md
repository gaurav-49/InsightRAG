# Deploying InsightRAG on a free server

The whole stack runs on one Linux server with Docker Compose, exactly as it runs locally. The
recommended free host is **Oracle Cloud Always Free**: its ARM server (up to 4 CPUs and 24 GB
RAM) is free indefinitely and easily runs the API, worker, Postgres and Redis together.

Why a single server rather than a platform such as Render or Railway: the API and the worker
share one folder for uploaded files, and free platform tiers either put apps to sleep, offer no
background workers, or can't share a disk between services.

What you end up with:

```
internet ──443/HTTPS──► Caddy ──► api:8080 ──► postgres, redis   (not reachable from the internet)
                                  worker ──┘
```

`docker-compose.prod.yml` adds automatic HTTPS (Caddy + Let's Encrypt), closes Postgres, Redis
and the API to the internet, turns off the dev-token endpoint, and restarts everything
automatically.

## One-command deploy

After creating the server (step 1) and opening ports 80/443 in Oracle's Security List (step 1a),
connect with SSH and run:

```bash
curl -fsSL https://raw.githubusercontent.com/gaurav-49/InsightRAG/main/deploy/install.sh | sudo bash
```

The installer ([deploy/install.sh](../deploy/install.sh)):

1. installs Docker, and adds swap on machines with less than 4 GB RAM
2. opens ports 80 and 443 in the server's own firewall (step 1b is done for you)
3. downloads InsightRAG into `/opt/insightrag`
4. creates `.env` with freshly generated `JWT_SECRET` and `POSTGRES_PASSWORD`
5. builds and starts the production stack with automatic HTTPS
6. prints the site address and an admin access token, valid for 7 days

With no domain set, the site is served at `https://<server-ip-with-dashes>.sslip.io`, which works
without any sign-up. To use your own domain, or real AI answers, pass settings in front of `bash`:

```bash
curl -fsSL https://raw.githubusercontent.com/gaurav-49/InsightRAG/main/deploy/install.sh | sudo DOMAIN=insightrag-gaurav.duckdns.org LLM_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-ant-... bash
```

**Updating** uses the same command. It pulls the latest code from GitHub and rebuilds, and keeps
your documents and secrets.

The sections below are the same process done by hand, plus operations and troubleshooting.

## 1. Create the server (Oracle Cloud)

1. Sign up at https://www.oracle.com/cloud/free/. A card is needed for identity verification;
   Always Free resources are not charged. Choose a home region close to you. Some regions often
   have no free ARM capacity; if creation fails with "Out of capacity", retry later or use
   another availability domain.
2. Go to **Compute → Instances → Create instance**:
   * **Image**: Canonical Ubuntu 24.04
   * **Shape**: Ampere, `VM.Standard.A1.Flex`, **2 OCPUs and 12 GB** memory (the free allowance
     is 4 OCPUs and 24 GB in total)
   * **Networking**: keep the default virtual network, and assign a public IPv4 address
   * **SSH keys**: "Generate a key pair for me" and **download the private key**
   * **Boot volume**: 50–100 GB (up to 200 GB is free)
3. Click **Create** and note the instance's **public IP address**.

### Open ports 80 and 443

Oracle blocks web traffic in two places, and both must be opened.

**a) Cloud firewall.** Instance → **Subnet** → **Security Lists** → *Default Security List* →
**Add Ingress Rules**. Add two rules, each with source `0.0.0.0/0`, protocol TCP, destination
ports `80` and `443`.

**b) Server firewall** (after connecting in step 3):

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
```

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
```

```bash
sudo netfilter-persistent save
```

## 2. Get a free domain name

HTTPS certificates need a hostname. Two free options:

* **DuckDNS** (recommended): sign in at https://www.duckdns.org, create a subdomain such as
  `insightrag-gaurav`, and set its IP to the server's public IP. Your domain is
  `insightrag-gaurav.duckdns.org`.
* **sslip.io** (no sign-up): for IP `140.238.10.20` use `140-238-10-20.sslip.io`.

If you already own a domain, add an `A` record pointing to the server IP instead.

## 3. Install Docker on the server

From your Mac, connect with the downloaded key:

```bash
chmod 600 ~/Downloads/ssh-key-*.key
```

```bash
ssh -i ~/Downloads/ssh-key-*.key ubuntu@SERVER_IP
```

On the server, install Docker, and allow your user to run it:

```bash
curl -fsSL https://get.docker.com | sudo sh
```

```bash
sudo usermod -aG docker ubuntu && exit
```

Connect again (so the group change applies), then check:

```bash
docker compose version
```

## 4. Deploy

On the server:

```bash
git clone https://github.com/gaurav-49/InsightRAG.git && cd InsightRAG
```

```bash
cp deploy/.env.production.example .env
```

Generate two secrets. Run this twice and copy each output:

```bash
openssl rand -base64 48
```

Edit `.env` (`nano .env`) and set:

* `DOMAIN`: your hostname from step 2
* `JWT_SECRET`: the first secret
* `POSTGRES_PASSWORD`: the second secret
* Optional, for real AI answers: `LLM_PROVIDER=anthropic` and `ANTHROPIC_API_KEY=...`

Start everything:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --wait
```

The first build takes about 5–10 minutes. Then open `https://YOUR_DOMAIN`. The console shows
"status UP" once it's ready.

## 5. Get an access token

The **Get dev token** button is disabled in production so that strangers can't upload documents.
Create tokens on the server with the signing secret:

```bash
python3 scripts/mint_token.py --scopes query admin --ttl 86400 --secret "$(grep ^JWT_SECRET .env | cut -d= -f2-)"
```

Paste the token into the console's **Access token** box. Tokens with `--scopes query` can only
ask questions. Give those to people who should not upload or delete. `--ttl` is the lifetime in
seconds (86400 = 24 hours).

To load the sample documents, run this from your Mac against the server:

```bash
INSIGHTRAG_API=https://YOUR_DOMAIN worker/.venv/bin/python scripts/seed_corpus.py
```

This uses the dev-token endpoint, so it won't work against production. Upload files through the
console instead, or temporarily set `DEV_TOKEN_ENABLED=true` in `.env`, restart, seed, then set it
back to `false`.

## 6. Day-to-day operations

All commands run on the server, inside `InsightRAG/`. Save typing with an alias:

```bash
echo "alias dc='docker compose -f docker-compose.yml -f docker-compose.prod.yml'" >> ~/.bashrc && source ~/.bashrc
```

| Task | Command |
|---|---|
| Status | `dc ps` |
| Logs | `dc logs -f api worker` |
| Update to the latest code | `git pull && dc up -d --build --wait` |
| Restart | `dc restart` |
| Stop | `dc down` (keeps data) |
| Health | `curl -s https://YOUR_DOMAIN/api/v1/health` |
| Metrics (server only) | `dc exec api curl -s localhost:8080/api/v1/metrics` |
| More workers | `dc up -d --scale worker=2` |

### Backups

Documents, chunks and settings live in the `pgdata` volume, and uploaded files in `uploads`. A
nightly database dump:

```bash
(crontab -l 2>/dev/null; echo "0 3 * * * cd ~/InsightRAG && docker compose exec -T postgres pg_dump -U insightrag insightrag | gzip > ~/backup-\$(date +\%F).sql.gz && find ~ -name 'backup-*.sql.gz' -mtime +7 -delete") | crontab -
```

Copy backups off the server now and then (for example with `scp` to your Mac).

### Hosting another app on the same server

InsightRAG's Caddy above binds ports 80 and 443 by itself. A second app on
the same box with its own Caddy (GYM OS, say) will fail to bind those same
ports — only one Caddy can hold them.

The fix, and the one-command migration to it, live in the other app's repo:
[gaurav-49/Gym-OS `docs/deployment.md`, "Hosting alongside another
app"](https://github.com/gaurav-49/Gym-OS/blob/main/docs/deployment.md#hosting-alongside-another-app-on-the-same-server).
It replaces both apps' own Caddy with one shared Caddy that routes to each by
hostname; `docker-compose.shared-edge.yml` in this repo is the InsightRAG
half of that — it drops InsightRAG's own Caddy service and joins the shared
network instead, keeping everything else (the memory ceilings, the disabled
dev-token endpoint, `JWT_SECRET` required) exactly as `docker-compose.prod.yml`
has it.

## Troubleshooting

| Problem | Fix |
|---|---|
| Browser can't connect at all | Ports 80/443 not open: check both the Security List (1a) and `iptables` (1b) |
| Certificate error, or Caddy logs mention ACME | The domain doesn't point to the server IP yet. Check with `ping YOUR_DOMAIN`, then `dc restart caddy` |
| `set DOMAIN in .env` / `set JWT_SECRET in .env` | A required value is missing from `.env` |
| Build fails with an out-of-memory error | Give the instance more memory (up to 24 GB is free), or build on a larger shape once |
| Health `DOWN` | `dc logs postgres api`: usually a wrong `POSTGRES_PASSWORD` after the database was first created with another one. Either restore the original password, or wipe the data with `dc down -v` |

## Security checklist

* `DEV_TOKEN_ENABLED` is `false` (enforced by `docker-compose.prod.yml`).
* `JWT_SECRET` and `POSTGRES_PASSWORD` are long random values, and `.env` is never committed
  (it is in `.gitignore`).
* Only ports 22, 80 and 443 are open. Postgres and Redis are not published.
* Keep the server patched: `sudo apt update && sudo apt upgrade -y` monthly.
