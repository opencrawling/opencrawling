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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard(false, List.of());

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
        "::1", "fc00::1", "fe80::1", "::ffff:10.0.0.1", "64:ff9b::a9fe:a9fe", "224.0.0.1"})
    void blocksInternalAddresses(String address) {
        assertThatThrownBy(() -> guard.resolve(address)).isInstanceOf(SsrfGuard.BlockedAddressException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.215.14", "8.8.8.8", "2606:4700:4700::1111"})
    void allowsPublicAddresses(String address) {
        assertThatCode(() -> guard.resolve(address)).doesNotThrowAnyException();
    }

    @Test
    void allowlistNeedsAllowPrivateNetworks() {
        SsrfGuard allowlistOnly = new SsrfGuard(false, List.of("127.0.0.0/8"));
        assertThatThrownBy(() -> allowlistOnly.resolve("127.0.0.1")).isInstanceOf(SsrfGuard.BlockedAddressException.class);

        SsrfGuard allowed = new SsrfGuard(true, List.of("127.0.0.0/8"));
        assertThatCode(() -> allowed.resolve("127.0.0.1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> allowed.resolve("10.0.0.1")).isInstanceOf(SsrfGuard.BlockedAddressException.class);
    }

    @Test
    void allowlistMatchesHostNames() {
        SsrfGuard allowed = new SsrfGuard(true, List.of("localhost"));
        assertThatCode(() -> allowed.resolve("localhost")).doesNotThrowAnyException();
    }

    @Test
    void cidrContainment() {
        assertThat(Cidr.parse("10.0.0.0/8").contains(java.net.InetAddress.ofLiteral("10.255.0.1"))).isTrue();
        assertThat(Cidr.parse("100.64.0.0/10").contains(java.net.InetAddress.ofLiteral("100.128.0.1"))).isFalse();
    }
}
