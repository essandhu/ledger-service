# README media — how it is made

Everything in this directory is generated from a **running stack**. Nothing is mocked, staged in
an image editor, or hand-drawn: the GIF is a recording of assertion-bearing HTTP calls, and both
screenshots are of live pages served by the compose services. If an invariant regressed, the
capture fails instead of producing a pretty lie.

| File | What it is | Made by |
|---|---|---|
| `demo.gif` | ~21 s terminal recording of `tour.sh` | `demo.tape` via [VHS](https://github.com/charmbracelet/vhs) |
| `openapi.png` | Swagger UI rendering the generated `/v3/api-docs` | `capture.mjs` (Playwright) |
| `grafana.png` | The provisioned dashboard with the drift gauges firing | `capture.mjs` (Playwright) |

The architecture and write-path diagrams in the README are Mermaid, inline in `README.md` —
GitHub renders them natively, so there is no image to regenerate.

## Regenerating

Start the stack first; both capture steps assume it is already up and healthy.

```sh
docker compose --profile observability up -d --build --wait
```

### demo.gif

`tour.sh` is a ~20-second cut of `scripts/demo.sh` — same guarantees, same assertions, minus the
rebuild. It is safe to run directly (`docs/media/tour.sh`) and safe to re-run: account names and
idempotency keys are namespaced per run.

VHS needs `ttyd` and `ffmpeg`, which rules out a native Windows run, so the recorder is a
container on this repo's own compose network, reaching the services by name:

```sh
docker build -f docs/media/Dockerfile.vhs -t ledger-vhs docs/media
docker run --rm --network ledger_default -v "$PWD:/vhs" \
  -e BASE=http://app:8080 -e KC=http://keycloak:8080 -e PGHOST=postgres \
  ledger-vhs docs/media/demo.tape
```

On Linux/macOS with `vhs`, `curl`, `jq` and `psql` on `PATH`, `vhs docs/media/demo.tape` works
directly — the tape's defaults point at `localhost`.

### openapi.png and grafana.png

```sh
cd docs/media
npm install && npx playwright install chromium
node capture.mjs
```

Two things worth knowing about `capture.mjs`:

- The whole API sits behind a bearer token (only `/actuator/health` is anonymous), so the browser
  context carries a real `client_credentials` token minted from the compose Keycloak.
- A dashboard screenshot reading zero would say nothing about I15, so the script induces the same
  out-of-band corruption `scripts/demo.sh` does — a superuser `UPDATE` that the application's own
  role is not granted — waits for Prometheus to scrape the gauges, shoots, then repairs the
  snapshot by recomputation and re-runs the sweep. **The stack is left `CLEAN`**; the script fails
  loudly if the repair does not verify.

## Conventions

- Keep `demo.gif` under 10 MB so GitHub renders it inline (it is ~240 KB today).
- `demo.tape`'s geometry is sized so the whole tour fits without wrapping or scrolling — the last
  frame is a readable still. Widening a `tour.sh` line past 105 columns means resizing the tape.
- `node_modules/` is git-ignored; `package-lock.json` is committed so a regenerated screenshot
  comes from the same Playwright.
