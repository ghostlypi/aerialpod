"""Direct device-to-device sync between AerialPod installs on one network.

gpodder.net is the bridge to the phone, but its protocol has no concept of a
queue — so a second desktop can never learn the order you dragged episodes
into, what you pinned, or what you threw out. That state only exists locally,
which is what this package replicates: devices paired once by a shared secret
exchange user intent, per-podcast settings, and live playback positions over
an encrypted unicast channel.

Discovery is deliberately unicast-only (no mDNS, no broadcast) so it works
over a WireGuard tunnel exactly as it does on a home LAN — see discovery.py.
"""
