package hacktrack.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Guards user-supplied URLs against server-side request forgery.
 *
 * <p>The plain HTTP fetcher only ever reads public web pages, but the headless
 * browser fallback follows redirects and loads sub-resources itself. Without this
 * check a user could point HackTrack at {@code http://localhost:8080/} or a cloud
 * metadata address and have the browser render the result back into the UI.
 *
 * <p>Every host is resolved and each resulting IP is checked, which also blocks
 * the common DNS-rebinding and decimal/octal-IP obfuscation tricks.
 */
final class UrlSafety {

    private UrlSafety() {
    }

    /**
     * Ensures the URI is a public http(s) address.
     *
     * @throws AiScheduleException {@code INVALID_URL} when the host is blank or resolves
     *                             to a loopback, link-local, site-local, multicast or
     *                             otherwise non-public address
     */
    static void requirePublicHttpUrl(URI uri) {
        if (uri == null) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "No website address was provided.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "Only http and https website addresses can be analysed.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "That address is missing a website name. Please check the URL and try again.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                    "Could not resolve that website name. Check the URL and try again.", e);
        }
        if (addresses.length == 0) {
            throw new AiScheduleException(AiScheduleException.Code.WEBSITE_UNAVAILABLE,
                    "Could not resolve that website name. Check the URL and try again.");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                        "That address is not a public website and cannot be analysed.");
            }
        }
    }

    /** True when the address is a routable public internet address. */
    static boolean isPublic(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(address)) {
            return false;
        }
        // IPv4-compatible and unique-local IPv6 ranges that the checks above miss.
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                return false; // 100.64.0.0/10 carrier-grade NAT
            }
            if (first == 192 && second == 0) {
                return false; // 192.0.0.0/24 IETF protocol assignments
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false; // 198.18.0.0/15 benchmarking
            }
            if (first >= 240) {
                return false; // 240.0.0.0/4 reserved
            }
        } else if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return false; // fc00::/7 unique local
            }
            if (first == 0xFE && (bytes[1] & 0xC0) == 0x80) {
                return false; // fe80::/10 link local
            }
            // IPv4-mapped (::ffff:a.b.c.d) must be judged on the embedded IPv4 address.
            if (isIpv4Mapped(bytes)) {
                try {
                    return isPublic(InetAddress.getByAddress(java.util.Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 100
                && (bytes[1] & 0xFF) >= 64
                && (bytes[1] & 0xFF) <= 127;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    /** Convenience for callers that only have a URL string. */
    static void requirePublicHttpUrl(String url) {
        try {
            requirePublicHttpUrl(URI.create(url.trim()));
        } catch (IllegalArgumentException e) {
            throw new AiScheduleException(AiScheduleException.Code.INVALID_URL,
                    "That does not look like a valid web address. Please check the URL and try again.");
        }
    }

    /** Lower-cased host without a leading {@code www.}, for same-site comparisons. */
    static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.startsWith("www.") ? lower.substring(4) : lower;
    }
}
