"""Finding peers without multicast.

mDNS and UDP broadcast are the usual answers here and both are wrong for this
app: neither crosses a WireGuard tunnel, which is point-to-point and carries no
link-local multicast at all. So discovery is unicast only, in three widening
steps:

1. peers we have authenticated with before, at their last known address;
2. addresses the user pinned by hand;
3. a bounded TCP sweep of the subnets this machine is actually on — which
   includes the tunnel's subnet, so a laptop on `10.0.0.0/24` over WireGuard
   finds the desktop exactly the way it would at home.

The sweep only probes subnets small enough to walk politely, and only touches
one port. A WireGuard config that assigns a bare `/32` leaves nothing to
sweep — that is what the manual peer list is for.
"""

from __future__ import annotations

import ipaddress
import logging
import socket
from concurrent.futures import ThreadPoolExecutor

from PySide6.QtNetwork import QAbstractSocket, QNetworkInterface

log = logging.getLogger(__name__)

DEFAULT_PORT = 47722
MAX_SUBNET_HOSTS = 1024   # /22 and smaller; anything larger isn't ours to scan
CONNECT_TIMEOUT = 0.4
SWEEP_WORKERS = 64


def local_networks() -> list[ipaddress.IPv4Network]:
    """IPv4 subnets this machine sits on, small enough to sweep."""
    nets: list[ipaddress.IPv4Network] = []
    for iface in QNetworkInterface.allInterfaces():
        flags = iface.flags()
        if not (flags & QNetworkInterface.InterfaceFlag.IsUp):
            continue
        if flags & QNetworkInterface.InterfaceFlag.IsLoopBack:
            continue
        for entry in iface.addressEntries():
            ip = entry.ip()
            if ip.protocol() != QAbstractSocket.NetworkLayerProtocol.IPv4Protocol:
                continue
            prefix = entry.prefixLength()
            if prefix <= 0 or prefix >= 32:
                # /32 is a point-to-point address with no subnet behind it —
                # common in hand-written WireGuard configs. Manual peers only.
                continue
            try:
                net = ipaddress.ip_network(f"{ip.toString()}/{prefix}", strict=False)
            except ValueError:
                continue
            if net.num_addresses > MAX_SUBNET_HOSTS or net in nets:
                continue
            nets.append(net)
    return nets


def own_addresses() -> set[str]:
    return {
        addr.toString()
        for addr in QNetworkInterface.allAddresses()
        if addr.protocol() == QAbstractSocket.NetworkLayerProtocol.IPv4Protocol
    }


def candidate_addresses() -> list[str]:
    """Every host address worth probing, minus this machine's own."""
    mine = own_addresses()
    seen: set[str] = set()
    out: list[str] = []
    for net in local_networks():
        for host in net.hosts():
            addr = str(host)
            if addr not in mine and addr not in seen:
                seen.add(addr)
                out.append(addr)
    return out


def probe(address: str, port: int, timeout: float = CONNECT_TIMEOUT) -> bool:
    """Is something accepting connections there? Whether it is a *peer* is the
    handshake's business — this only decides who is worth talking to."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            return sock.connect_ex((address, port)) == 0
    except OSError:
        return False


def sweep(
    port: int = DEFAULT_PORT,
    addresses: list[str] | None = None,
    *,
    timeout: float = CONNECT_TIMEOUT,
    workers: int = SWEEP_WORKERS,
) -> list[str]:
    """Blocking. Run it off the event loop — see LanService._sweep_worker."""
    targets = candidate_addresses() if addresses is None else addresses
    if not targets:
        return []
    log.debug("sweeping %d address(es) on port %d", len(targets), port)
    with ThreadPoolExecutor(max_workers=min(workers, len(targets))) as pool:
        results = pool.map(lambda addr: (addr, probe(addr, port, timeout)), targets)
        return [addr for addr, open_ in results if open_]
