package com.example.jobscheduler.executor;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.jobscheduler.api.ApiException;
import com.example.jobscheduler.config.HttpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class SsrfGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "127.0.0.1", "127.1", "2130706433", "10.1.2.3", "172.16.0.1",
            "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "fd00:ec2::254", "::ffff:127.0.0.1", "2002:7f00:1::",
            "64:ff9b::a9fe:a9fe", "2001:0::1"})
    void blocksNonPublicAndMetadataAddresses(String ip) throws Exception {
        var guard = guard(host -> new InetAddress[]{InetAddress.getByName(ip)});
        assertThatThrownBy(() -> guard.check(URI.create("https://target.example/hook"))).isInstanceOf(ApiException.class);
    }

    @Test
    void permitsPublicIpv4AndIpv6ButRejectsMixedPublicPrivateAnswers() throws Exception {
        var public4 = InetAddress.getByName("93.184.216.34");
        var public6 = InetAddress.getByName("2606:4700:4700::1111");
        assertThatCode(() -> guard(host -> new InetAddress[]{public4, public6}).check(URI.create("https://target.example")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard(host -> new InetAddress[]{public4, InetAddress.getLoopbackAddress()})
                .check(URI.create("https://target.example"))).isInstanceOf(ApiException.class);
    }

    @Test
    void resolvesAgainAndFailsClosedOnChangedOrMissingDns() throws Exception {
        var lookups = new AtomicInteger();
        var guard = guard(host -> new InetAddress[]{InetAddress.getByName(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "127.0.0.1")});
        guard.check(URI.create("https://target.example"));
        assertThatThrownBy(() -> guard.check(URI.create("https://target.example"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard(host -> { throw new UnknownHostException(); })
                .check(URI.create("https://target.example"))).isInstanceOf(ApiException.class);
    }

    @Test
    void allowlistMatchesOnlyExactHostsAndRejectsScopedIpv6OrCredentials() {
        var guard = new SsrfGuard(new HttpProperties(List.of("localhost")), host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        assertThatCode(() -> guard.check(URI.create("http://LOCALHOST.:8080"))).doesNotThrowAnyException();
        for (var url : List.of("http://localhost.evil.example", "http://evil-localhost", "http://[fe80::1%25eth0]",
                "http://user:secret@localhost", "file:///etc/passwd")) {
            assertThatThrownBy(() -> guard.check(URI.create(url))).isInstanceOf(ApiException.class);
        }
    }

    private SsrfGuard guard(SsrfGuard.Resolver resolver) {
        return new SsrfGuard(new HttpProperties(List.of()), resolver);
    }
}
