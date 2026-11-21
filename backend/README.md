# Universal Video Downloader backend

This directory contains a self-hosted Cobalt API deployment for the Android app.
Cobalt supports a broad, explicit list of services; it does **not** promise every
URL on the internet. Public/private access restrictions and DRM remain limitations.

## Deploy

1. Provision a Linux VPS with Docker Engine and the Docker Compose plugin.
2. Copy this directory to the server.
3. Copy `.env.example` to `.env` and set `API_URL` to the final HTTPS API origin.
4. Copy `keys.example.json` to `keys.json` and replace the example API key with a
   long random secret. Never commit real keys or `.env` to Git.
5. Put a TLS reverse proxy (for example Caddy or Nginx) in front of port 9000.
   Do not expose port 9000 directly to the public internet.
6. Run `docker compose up -d`.
7. Verify `https://YOUR-HOST/` returns Cobalt instance information.

The Android client must then be configured with this HTTPS endpoint and the same
API key. Do not put a production API key in a public repository or share it in chat.

## Supported services

See the upstream maintained list:
https://github.com/imputnet/cobalt/blob/main/api/README.md

This is a backend deployment kit, not a claim that the backend has already been
deployed. A reachable VPS/domain and its secret configuration are required before
the Android app can use it.
