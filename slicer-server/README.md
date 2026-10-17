# Filum Slicer

OrcaSlicer (command line) behind an HTTP API for the [Filum](../README.md) app, plus a small web UI.

```sh
docker compose up -d --build     # then open http://<server-ip>:8090
```

- [`Dockerfile`](Dockerfile): Ubuntu 24.04 with OrcaSlicer baked in.
- [`docker-compose.yml`](docker-compose.yml): generic setup. All variables are optional.
- [`deploy/`](deploy): TrueNAS SCALE, with the prebuilt image or without a registry.
- [`app/`](app): the server code (Python standard library only).

Installation, configuration, the API and how multicolor works are described in
[docs/slicer-server.md](../docs/slicer-server.md).
