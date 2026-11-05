/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.hc.client5.http.DnsResolver;

/**
 * Blocks requests to internal network addresses (SSRF protection, spec §11).
 *
 * <p>Used as the HTTP client's {@link DnsResolver}, so the check runs on the exact addresses the client
 * connects to. That also blocks DNS rebinding and is applied again on every redirect hop.
 */
public final class SsrfGuard implements DnsResolver {

    private static final List<Cidr> BLOCKED_RANGES = List.of(
        Cidr.parse("0.0.0.0/8"),        // "this" network
        Cidr.parse("10.0.0.0/8"),       // RFC 1918
        Cidr.parse("100.64.0.0/10"),    // carrier-grade NAT
        Cidr.parse("127.0.0.0/8"),      // loopback
        Cidr.parse("169.254.0.0/16"),   // link-local, including cloud metadata 169.254.169.254
        Cidr.parse("172.16.0.0/12"),    // RFC 1918
        Cidr.parse("192.0.0.0/24"),     // IETF protocol assignments
        Cidr.parse("192.168.0.0/16"),   // RFC 1918
        Cidr.parse("198.18.0.0/15"),    // benchmarking
        Cidr.parse("224.0.0.0/4"),      // multicast
        Cidr.parse("240.0.0.0/4"),      // reserved, broadcast
        Cidr.parse("::/128"),           // unspecified
        Cidr.parse("::1/128"),          // loopback
        Cidr.parse("fc00::/7"),         // unique local
        Cidr.parse("fe80::/10"),        // link-local
        Cidr.parse("ff00::/8"));        // multicast

    private final boolean allowPrivateNetworks;
    private final List<String> allowedHosts = new ArrayList<>();
    private final List<Cidr> allowedRanges = new ArrayList<>();

    public SsrfGuard(boolean allowPrivateNetworks, List<String> privateAllowlist) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        for (String entry : privateAllowlist) {
            if (entry.contains("/")) {
                allowedRanges.add(Cidr.parse(entry));
            } else {
                allowedHosts.add(entry.toLowerCase(Locale.ROOT));
            }
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (isBlocked(address) && !isAllowed(host, address)) {
                throw new BlockedAddressException(host, address);
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return resolve(host)[0].getCanonicalHostName();
    }

    /**
     * Checks a host before a request is sent, so blocked hosts fail fast with a clear reason.
     */
    public void check(String host) throws UnknownHostException {
        resolve(host);
    }

    static boolean isBlocked(InetAddress address) {
        InetAddress effective = embeddedIpv4(address);
        for (Cidr range : BLOCKED_RANGES) {
            if (range.contains(effective)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowed(String host, InetAddress address) {
        if (!allowPrivateNetworks) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (normalizedHost.equals(allowed)
                || (allowed.startsWith(".") && normalizedHost.endsWith(allowed))
                || (allowed.startsWith("*.") && normalizedHost.endsWith(allowed.substring(1)))) {
                return true;
            }
        }
        for (Cidr range : allowedRanges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the IPv4 address embedded in an IPv4-compatible or NAT64 (64:ff9b::/96) IPv6 address,
     * so it can't be used to reach a blocked IPv4 range.
     */
    private static InetAddress embeddedIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        boolean nat64 = bytes[0] == 0 && bytes[1] == 0x64 && bytes[2] == (byte) 0xff && bytes[3] == (byte) 0x9b
            && allZero(bytes, 4, 12);
        boolean compatible = allZero(bytes, 0, 12) && !(bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0);
        if (!nat64 && !compatible) {
            return address;
        }
        try {
            return Inet4Address.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException e) {
            return address;
        }
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Thrown when a host resolves to a blocked address. */
    public static final class BlockedAddressException extends UnknownHostException {
        public BlockedAddressException(String host, InetAddress address) {
            super("Blocked by SSRF guard: " + host + " resolves to internal address " + address.getHostAddress()
                + " (set allowPrivateNetworks and privateAllowlist to crawl it)");
        }
    }
}
