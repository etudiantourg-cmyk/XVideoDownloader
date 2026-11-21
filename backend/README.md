# Universal Video Downloader backend

This folder contains a Cobalt self-hosting template based on the upstream
deployment guide. It is **not deployed** and the Android app is not yet connected
to a live instance.

## What this engine can support

The upstream Cobalt project currently lists services including Instagram,
public Facebook videos, Twitter/X, TikTok, Reddit, Vimeo and YouTube, among
others. Its maintained support matrix and service-specific limitations are here:
https://github.com/imputnet/cobalt/blob/main/api/README.md

It cannot guarantee every URL on the internet. DRM, private posts, login walls,
geo-blocks, expired links and site-side changes can prevent downloads.

## Deployment requirements

You need an always-on Linux host that can run Docker Compose, a public domain,
and HTTPS via a reverse proxy. A Cloudflare Worker alone cannot run this Docker
service. Do not expose port 9000 directly to the public internet.

1. Copy `.env.example` to `.env` and set `API_URL` to the final public HTTPS URL.
2. Configure a TLS reverse proxy to forward to `127.0.0.1:9000`.
3. Run `docker compose up -d`.
4. Check the instance root URL and test a POST request according to the upstream API docs.
5. Configure the Android client with the deployed HTTPS endpoint.

The upstream currently documents API-key authentication as an optional
protection for public instances. Enable it only after following its current
key-file format and protection guide; never commit real keys or cookies.

## Upstream references

- API: https://github.com/imputnet/cobalt/blob/main/docs/api.md
- Deployment: https://github.com/imputnet/cobalt/blob/main/docs/run-an-instance.md
- Environment variables: https://github.com/imputnet/cobalt/blob/main/docs/api-env-variables.md
