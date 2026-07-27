# VHS tape for docs/media/demo.gif — records docs/media/tour.sh against a live stack.
#
# Regenerate (see docs/media/README.md):
#   docker compose --profile observability up -d --build --wait
#   docker build -f docs/media/Dockerfile.vhs -t ledger-vhs docs/media
#   docker run --rm --network ledger_default -v "$PWD:/vhs" \
#     -e BASE=http://app:8080 -e KC=http://keycloak:8080 -e PGHOST=postgres \
#     ledger-vhs docs/media/demo.tape
#
# Geometry note: the tour's widest line is 99 columns and it prints 38 lines, so the terminal is
# sized to hold the whole run without wrapping or scrolling — the GIF ends as a readable still.
# At this font size 920x780 gave 91x39; 1060x820 gives the ~105x41 the tour needs.

Output docs/media/demo.gif

Set Shell bash
Set FontSize 14
Set Width 1060
Set Height 820
Set Padding 20
Set Theme "Catppuccin Mocha"
Set TypingSpeed 45ms
Set Framerate 20

# The script's own pacing: it runs in ~2s unpaced, which is unreadable at 20fps.
Env PACE "0.45"

Type "docs/media/tour.sh"
Sleep 700ms
Enter

# The paced run takes ~14s; the margin covers a cold JIT on the first request.
Sleep 18s
Sleep 2s
