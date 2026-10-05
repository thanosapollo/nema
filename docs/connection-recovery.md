# Connection health and recovery

Nema checks the server, not only Android's socket-connected flag. Every physical
connection has its own XEP-0199 ping-failure listener. A failed ping immediately
makes that transport unavailable, closes it, and begins recovery. Callbacks and
probe results from an old account or connection attempt cannot retire a successor.

## Active detection and Android idle

While Android is running the process and allowing networking, the automatic ping
interval is 60 seconds. Smack 4.4.8 waits at least 120 seconds for the automatic
ping response (or the connection's reply timeout, if longer). With the normal
reply timeout, a silent connection is therefore detected within approximately
three minutes of its last inbound traffic, **plus scheduling delays**. Receiving
new traffic is itself liveness evidence and postpones that check.

Returning to the foreground or obtaining a usable, validated default network
also requests a server probe with a ten-second response timeout. Concurrent wakes
share one probe or recovery cycle. Network availability is only a reason to probe;
it is not proof that the XMPP server is reachable.

These are active-process bounds, not a promise of continuous background delivery.
Android Doze, suspended networking, process death and battery restrictions can
delay timers and probes. A foreground service does not bypass Doze. Nema does not
currently provide a push-based wake service or request a battery exemption.

## Recovery and controls

An ordinary server close or temporary network failure retains the account's
authorized connection intent. Nema makes up to five exponentially delayed
reconnection attempts per cycle. The delays total 31 seconds; connecting and
handshaking take additional time. If those attempts fail, Nema stays in
**Waiting to reconnect**, rather than silently abandoning the account. Returning
to Nema or a usable-network transition can start another bounded cycle.

Home (including search), conversations (including loading/error states) and the
Settings Session section show a small non-connected status with **Reconnect**.
Reconnect is bound to the displayed account, cannot switch back to a retired
account, and is disabled during connecting, switching or disconnecting. The normal
Connected state has no banner; the existing Tor-without-XMPP-TLS warning remains.
An explicit Reconnect can restart a deliberately stopped saved account.

**Stop** and **Sign out** revoke automatic recovery. A later foreground/network
wake cannot restart them. Authentication, certificate/TLS, configuration and
protocol failures are terminal, not network-retry loops; correct the problem
before deliberately reconnecting. Tor accounts still require Orbot and never
fall back to direct transport. Reconnect reloads the saved account's credentials
at authentication time; it does not introduce another plaintext password cache.

Reconnecting does not prove that an earlier send reached the server or recipient.
Pending, definitely unentered operations may resume through the outbox. A message
whose transport entry was possible remains **Delivery unknown** and is never
blindly resent by connection recovery. Local history, drafts and account data are
not deleted by recovery.
