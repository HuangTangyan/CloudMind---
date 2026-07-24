package com.cloudmind.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class AiEndpointSecurityService {
    private final List<String> allowedHosts;
    private final AddressResolver addressResolver;

    public AiEndpointSecurityService(
            @Value("${cloudmind.ai.allowed-hosts:api.deepseek.com,api.openai.com}")
            String allowedHosts
    ) {
        this(allowedHosts, InetAddress::getAllByName);
    }

    AiEndpointSecurityService(String allowedHosts, AddressResolver addressResolver) {
        this.allowedHosts = Arrays.stream(
                        allowedHosts == null ? new String[0] : allowedHosts.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        this.addressResolver = addressResolver;
    }

    public URI validateBaseUrl(String rawBaseUrl) {
        URI uri;
        try {
            uri = new URI(rawBaseUrl == null ? "" : rawBaseUrl.trim()).normalize();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("AI Base URL 格式不正确");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("AI Base URL 只允许 HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("AI Base URL 必须包含有效域名");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("AI Base URL 不允许包含账号、查询参数或片段");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("AI Base URL 只允许使用标准 HTTPS 端口 443");
        }

        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        if (!isAllowedHost(host)) {
            throw new IllegalArgumentException("AI Base URL 域名不在可信白名单中");
        }
        validateResolvedAddresses(host);

        try {
            return new URI(
                    "https",
                    null,
                    host,
                    uri.getPort(),
                    normalizePath(uri.getPath()),
                    null,
                    null
            );
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("AI Base URL 格式不正确");
        }
    }

    public URI validateRequestUri(URI uri) {
        return validateBaseUrl(uri == null ? "" : uri.toString());
    }

    private boolean isAllowedHost(String host) {
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.")) {
                String suffix = allowed.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) return true;
            } else if (host.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private void validateResolvedAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = addressResolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("AI Base URL 域名无法解析");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("AI Base URL 域名无法解析");
        }
        for (InetAddress address : addresses) {
            if (!isGloballyRoutable(address)) {
                throw new IllegalArgumentException("AI Base URL 不允许解析到本机、内网或保留地址");
            }
        }
    }

    private boolean isGloballyRoutable(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isPublicIpv4(bytes);
        if (bytes.length != 16) return false;

        if ((bytes[0] & 0xfe) == 0xfc) return false;
        if ((bytes[0] & 0xff) == 0x20
                && (bytes[1] & 0xff) == 0x01
                && (bytes[2] & 0xff) == 0x0d
                && (bytes[3] & 0xff) == 0xb8) {
            return false;
        }
        boolean mappedIpv4 = true;
        for (int i = 0; i < 10; i++) mappedIpv4 &= bytes[i] == 0;
        mappedIpv4 &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
        return !mappedIpv4 || isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
    }

    private boolean isPublicIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && ((second == 0 && (third == 0 || third == 2)) || second == 168)) return false;
        if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) return false;
        return first != 203 || second != 0 || third != 113;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replaceAll("/{2,}", "/");
        return normalized.endsWith("/") && normalized.length() > 1
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
