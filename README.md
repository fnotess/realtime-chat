# realtime-chat

Real-time 1:1 messaging: Spring Boot with a hand-written WebSocket server, Postgres, and a React client.

## Run it

```bash
docker compose up --build
```

Open http://localhost:3000 in a normal and a private window (the session cookie is shared across a
browser profile's windows) and register a user in each.

- `web` (nginx) is the only published port. It serves the client, and proxies `/api/` to the
  server's REST port and `/ws` to its WebSocket port, so everything is on one origin.
- Credentials: copy `.env.example` to `.env`. Without a `.env` the stack falls back to local-only
  defaults, which is acceptable only because the database isn't published.
- The server image skips tests (Testcontainers needs a Docker daemon, and there is none inside
  `docker build`). Run them separately: `cd server && ./mvnw test`, `cd client && npm test`.

### Local development

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db   # Postgres on 127.0.0.1:5432
cd server && ./mvnw spring-boot:run                                       # REST :8080, WebSocket :8081
cd client && npm install && npm run dev                                   # http://localhost:5173
```

## Production notes

- **HTTPS:** the session cookie is not `Secure`, because `http://localhost` can't carry a Secure
  cookie. Behind HTTPS set `CHAT_AUTH_COOKIESECURE=true` (and the `__Host-` prefix becomes possible),
  add the https origin to `CHAT_AUTH_ALLOWEDORIGINS`, and the client switches to `wss:` by itself.
- **Client IPs behind a proxy:** per-IP limits use `X-Forwarded-For`, believed only when the direct
  peer is listed in `CHAT_AUTH_TRUSTEDPROXIES` (nginx's fixed address in compose). Add any load
  balancer in front of it to that list.
- **More than one server:** `docker compose up --scale server=2` would break delivery. The connection
  registry is in memory, so a message is fanned out only to sockets on the node that stored it, and
  a recipient connected to the other node never gets it live (only on its next sync). Several
  replicas need cross-node fan-out (Redis pub/sub or Postgres `LISTEN/NOTIFY`), with sticky sessions
  for the WebSocket. The in-memory login rate limits and connection counts would move to shared
  storage (e.g. Redis) as well.
