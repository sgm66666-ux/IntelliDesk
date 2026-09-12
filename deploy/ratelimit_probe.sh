#!/bin/sh
# Independent Wave5 trusted-proxy / login rate-limit probe.
# Usage: ratelimit_probe.sh <url> <n>
# Prints HTTP status of <n> consecutive POSTs (dummy creds) to <url>.
URL="$1"
N="$2"
i=0
while [ "$i" -lt "$N" ]; do
  code=$(wget -qS -O /dev/null --post-data='{"username":"probe","password":"probe"}' --header='Content-Type: application/json' "$URL" 2>&1 | tr '\r\n' ' ' | sed -n 's/.*HTTP\/1\.1 \([0-9][0-9][0-9]\).*/\1/p')
  printf '%s ' "$code"
  i=$((i+1))
done
echo