#!/bin/sh
# Spoof probe: same source IPv4 (backend container) sends forged forwarding headers.
# If nginx overwrote X-Forwarded-For/Forwarded with $remote_addr, backend still sees
# the same principal => still 429. A bypass would return 401/200.
URL="http://intellidesk-web/api/auth/login"
body='{"username":"probe","password":"probe"}'
probe () {
  local hdr="$1"
  code=$(wget -qS -O /dev/null --header="Content-Type: application/json" --header="$hdr" --post-data="$body" "$URL" 2>&1 | tr '\r\n' ' ' | sed -n 's/.*HTTP\/1\.1 \([0-9][0-9][0-9]\).*/\1/p')
  printf '%s' "$code"
}
echo -n "forged XFF 1.1.1.1      -> "; probe 'X-Forwarded-For: 1.1.1.1'; echo
echo -n "forged XFF 2.2.2.2      -> "; probe 'X-Forwarded-For: 2.2.2.2'; echo
echo -n "forged Forwarded for=3.3.3.3 -> "; probe 'Forwarded: for=3.3.3.3'; echo
echo -n "forged XFF list 9.9.9.9,8.8.8.8 -> "; probe 'X-Forwarded-For: 9.9.9.9, 8.8.8.8'; echo