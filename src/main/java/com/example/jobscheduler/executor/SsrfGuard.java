package com.example.jobscheduler.executor;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.jobscheduler.api.ApiException;
import com.example.jobscheduler.config.HttpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SsrfGuard {
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Set<String> allowed;
    private final Resolver resolver;

    @Autowired
    public SsrfGuard(HttpProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    SsrfGuard(HttpProperties properties, Resolver resolver) {
        allowed = properties.allowedHosts().stream().map(SsrfGuard::normalize).collect(Collectors.toUnmodifiableSet());
        this.resolver = resolver;
    }

    /** Check every A/AAAA answer, both when saving configuration and immediately before execution. */
    public void check(URI uri) {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw denied();
        }
        var host = normalize(uri.getHost());
        if (host.contains("%")) {
            throw denied(); // Scoped IPv6 addresses must not select a local network interface.
        }
        if (allowed.contains(host)) {
            return;
        }
        try {
            var addresses = resolver.resolve(host);
            if (addresses.length == 0) {
                throw denied();
            }
            for (var address : addresses) {
                if (blocked(address)) {
                    throw denied();
                }
            }
        } catch (UnknownHostException ex) {
            throw denied();
        }
    }

    private static String normalize(String host) {
        var value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    static boolean blocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        var bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            return first == 0 || first == 10 || first == 127 || first >= 224
                    || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127;
        }
        // IPv6 unique-local and transition mechanisms that can tunnel to private IPv4.
        return (first & 0xfe) == 0xfc
                || first == 0x20 && second == 0x02 // 6to4
                || first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0 // Teredo
                || first == 0 && second == 0x64 && bytes[2] == (byte) 0xff && bytes[3] == (byte) 0x9b // NAT64
                || first == 0 && second == 0; // IPv4-compatible forms and reserved low IPv6 space
    }

    private ApiException denied() {
        return ApiException.invalid("payload.url", "HTTP destination is blocked or cannot be resolved");
    }
}
