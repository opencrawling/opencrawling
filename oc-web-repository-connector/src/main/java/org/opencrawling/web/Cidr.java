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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An IPv4 or IPv6 address range in CIDR notation, such as {@code 10.0.0.0/8} or {@code fc00::/7}.
 */
public record Cidr(byte[] network, int prefixLength) {

    /**
     * Parses a CIDR range. The address part must be a literal IP, so no DNS lookup happens.
     */
    public static Cidr parse(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Not a CIDR range: " + cidr);
        }
        String address = cidr.substring(0, slash).trim();
        if (!address.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Not an IP literal: " + address);
        }
        try {
            byte[] network = InetAddress.getByName(address).getAddress();
            int prefixLength = Integer.parseInt(cidr.substring(slash + 1).trim());
            if (prefixLength < 0 || prefixLength > network.length * 8) {
                throw new IllegalArgumentException("Invalid prefix length: " + cidr);
            }
            return new Cidr(network, prefixLength);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR range: " + cidr, e);
        }
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
