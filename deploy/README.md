# Making Streama public (free hosting, no computer of your own needed)

Streama needs two things a typical free "web app" host does not give you: a
**long-running Java process** and a **persistent disk** for the database. Free tiers of
Render, Railway, Fly.io, Vercel and Netlify fail at least one of those (Render's free
services have no persistent disk and sleep; Vercel/Netlify cannot run a JVM server at
all).

Video storage and streaming are handled by **Mux**, so this server never stores or serves
video files: small server, no giant disk, and your viewers stream from Mux's CDN instead
of from the VM.

The one genuinely free option that fits is **Oracle Cloud's Always Free** tier — a real
virtual machine you keep for as long as the account exists, with 4 ARM cores / 24 GB
RAM, 200 GB of disk and 10 TB of outbound traffic per month. Nothing runs on your own
computer; you only need a browser.

> The always-free plan requires a credit card for identity verification (a temporary
> ~$1 hold, not charged). Video sites also burn egress: 10 TB/month is plenty for
> personal use, but a hit show shared with hundreds of viewers would exceed it.

---

## 1. Create the free server

1. Sign up at <https://cloud.oracle.com> (home region can't be changed later — pick one
   near your viewers; "us-ashburn-1" / "us-phoenix-1" are usually well stocked).
2. **Compute → Instances → Create instance**.
   - **Image:** Canonical Ubuntu 22.04
   - **Shape:** Ampere → `VM.Standard.A1.Flex`, **4 OCPUs / 24 GB** (all covered by
     Always Free). If you get "out of capacity", try another Availability Domain, or
     retry later; alternatively use `VM.Standard.E2.1.Micro` and change
     `JAVA_TOOL_OPTIONS` to `-Xmx700m` in `deploy/image/Dockerfile`.
   - **Networking:** keep the default VCN, make sure *Assign a public IPv4 address* is on.
   - **SSH keys:** upload or generate one — you'll use it to run commands.
3. Note the instance's **public IP** once it is running.

## 2. Open ports 80 and 443

Oracle blocks them in two places; both must be opened.

**a) Cloud firewall:** VCN → Security Lists → default list → *Add Ingress Rule*, twice:
source `0.0.0.0/0`, protocol TCP, destination port `80`, then again for `443`.

**b) Ubuntu's own firewall** (Oracle's images reject everything else by default) — SSH in
(`ssh -i your-key opc@<public-ip>`) and run:

```sh
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 3. Install Docker and start Streama

```sh
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER      # then log out and back in
git clone https://github.com/ProudDoggoStudios/streama.git
cd streama/deploy
cp .env.example .env
```

Edit `.env` and set `STREAMA_DOMAIN`:

- **Have a domain?** Point an `A` record at the public IP and set
  `STREAMA_DOMAIN=streama.example.com`. Caddy then fetches and renews a free Let's
  Encrypt HTTPS certificate by itself.
- **No domain?** Free subdomains from <https://www.duckdns.org> work the same way (set it
  to the IP, use `yourname.duckdns.org`).
- **Just testing?** Leave it empty: the site is served over plain HTTP on the IP.

Then:

```sh
docker compose up -d --build
```

The first build downloads the ~100 MB release jar and takes a couple of minutes. Follow
the boot with `docker compose logs -f app` until you see
`Grails application running at`. Your site is now live at your domain or IP.

## 4. First-run settings (once, in the browser)

1. Log in with **Admin / 4upocx0v** (the seeded first-run account — created only on an empty
   database; change the password in Admin → Users whenever you like).
2. On the **Settings** page:
   - **Mux Token ID / Mux Token Secret:** your Mux access token, from
     <https://dashboard.mux.com/settings/access-tokens>. Skip both if you already put
     `MUX_TOKEN_ID` / `MUX_TOKEN_SECRET` in `.env`. Videos cannot be added until Mux is
     configured.
   - **TheMovieDB API key:** get one free at
     <https://www.themoviedb.org/settings/api> (needed before `Save Settings` unlocks)
   - **Base URL:** your public address, e.g. `https://streama.example.com` — this is what
     makes video links and redirects work for remote viewers.
3. **Save Settings**, then **Validate** each value (the Mux validation calls the Mux API).

## 5. Day-to-day

| Task | Command (run in `deploy/`) |
| --- | --- |
| Update the app | `git pull && docker compose up -d --build` |
| Upload a video | Upload it on a movie/show page in the app — it goes to Mux, not to this server |
| Backup database + uploads | `docker run --rm -v deploy_streama_data:/data -v "$PWD":/backup alpine tar czf /backup/streama-backup.tgz /data` |
| Restore | `docker run --rm -v deploy_streama_data:/data -v "$PWD":/backup alpine sh -c 'cd / && tar xzf /backup/streama-backup.tgz'` |
| Logs | `docker compose logs -f` |

Everything persistent (the database at `/data/streamadb` and the images/subtitles at
`/data/streama`) lives in the `streama_data` volume, so rebuilding or updating the
container never loses data. Uploaded videos are not on this server at all — they are on
Mux, so deleting the Mux access token (or the account) would lose them; keep the token
safe.

## Notes

- The image builds from Streama's **official v1.11.2 release** (same as upstream's
  `docker/Dockerfile`). If you change code in this repository, run `./gradlew assemble`
  and copy the resulting jar in as `/app/streama.jar` instead — see
  `deploy/image/Dockerfile`.
- Caddy handles HTTPS and forwards `X-Forwarded-Proto`, so redirects come out as
  `https://` — no extra proxy configuration needed.
- Prefer a different host? Any always-on Linux server with Docker works identically;
  a PaaS with a "persistent volume + Dockerfile" option (e.g. a paid Render instance with
  a 250 GB disk) will also run this compose file.
