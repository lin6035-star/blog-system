package com.hailin.blogsystem.service;

import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

@Service
public class IpLocationService {

    private static final String UNKNOWN_LOCATION = "未知";
    private static final String LOCAL_NETWORK = "本地网络";
    private static final Set<String> ISO_COUNTRY_CODES = Set.of(Locale.getISOCountries());

    private final Searcher searcher;

    public IpLocationService() {
        try (InputStream inputStream = new ClassPathResource("ip2region_v4.xdb").getInputStream()) {
            this.searcher = Searcher.newWithBuffer(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("加载 IP 属地数据库失败", e);
        }
    }

    public String getLocation(String ip) {
        return getLocation(ip, null);
    }

    public String getLocation(String ip, String cloudflareCountryCode) {
        if (ip == null || ip.isBlank()) {
            return UNKNOWN_LOCATION;
        }

        String normalizedIp = ip.trim();
        if (isLocalAddress(normalizedIp)) {
            return LOCAL_NETWORK;
        }
        if (isIpv4(normalizedIp)) {
            try {
                return toCoarseLocation(searcher.search(normalizedIp));
            } catch (Exception e) {
                return UNKNOWN_LOCATION;
            }
        }

        return isIpv6(normalizedIp)
                ? toChineseCountryName(cloudflareCountryCode)
                : UNKNOWN_LOCATION;
    }

    private String toCoarseLocation(String region) {
        if (region == null || region.isBlank()) {
            return UNKNOWN_LOCATION;
        }

        String[] parts = region.split("\\|", -1);
        String country = part(parts, 0);
        String province = part(parts, 1);
        String countryCode = part(parts, 4);

        if ("中国".equals(country)) {
            return isKnown(province) ? province : country;
        }

        String chineseCountryName = toChineseCountryName(countryCode);
        if (!UNKNOWN_LOCATION.equals(chineseCountryName)) {
            return chineseCountryName;
        }

        return isKnown(country) ? country : UNKNOWN_LOCATION;
    }

    private boolean isLocalAddress(String ip) {
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }

        String lowerIp = ip.toLowerCase(Locale.ROOT);
        if (lowerIp.startsWith("fc") || lowerIp.startsWith("fd") || lowerIp.startsWith("fe80:")) {
            return true;
        }

        if (!isIpv4(ip)) {
            return false;
        }

        String[] parts = ip.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);

        return first == 10
                || first == 127
                || first == 0
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 100 && second >= 64 && second <= 127);
    }

    private boolean isIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private boolean isIpv6(String ip) {
        if (!ip.contains(":")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address instanceof Inet6Address;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private String toChineseCountryName(String countryCode) {
        if (countryCode == null) {
            return UNKNOWN_LOCATION;
        }

        String normalizedCode = countryCode.trim().toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRY_CODES.contains(normalizedCode)) {
            return UNKNOWN_LOCATION;
        }

        try {
            String countryName = new Locale.Builder()
                    .setRegion(normalizedCode)
                    .build()
                    .getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
            return countryName.isBlank() ? UNKNOWN_LOCATION : countryName;
        } catch (RuntimeException e) {
            return UNKNOWN_LOCATION;
        }
    }

    private String part(String[] parts, int index) {
        return index < parts.length ? parts[index] : "";
    }

    private boolean isKnown(String value) {
        return value != null && !value.isBlank() && !"0".equals(value);
    }
}
