# Recorder image for docs/media/demo.tape.
#
# VHS needs ttyd + ffmpeg, neither of which has a working Windows build, so the recording runs
# in a container on this repo's own compose network (`ledger_default`) and talks to the live
# stack by service name. The upstream image ships ttyd/ffmpeg but not the three tools the tour
# actually uses, so they are added here — pinned to nothing on purpose: this image is a local
# recording harness, not a shipped artifact.
FROM ghcr.io/charmbracelet/vhs:latest

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl jq postgresql-client ca-certificates \
 && rm -rf /var/lib/apt/lists/*
