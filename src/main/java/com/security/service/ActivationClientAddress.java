package com.security.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Arrays;

/** No forwarded header is trusted unless the socket peer is explicitly allowlisted. */
@Component
public class ActivationClientAddress {
    private final List<IpAddressMatcher> trusted;
    public ActivationClientAddress(Environment env) {
        trusted = Arrays.stream(env.getProperty("app.activation.trusted-proxies", "").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).map(cidr -> {
                if (cidr.endsWith("/0")) throw new IllegalArgumentException("An unrestricted activation proxy is not allowed");
                return new IpAddressMatcher(cidr);
            }).toList();
    }
    public String resolve(HttpServletRequest request) {
        if (request == null) return null;
        String peer = request.getRemoteAddr();
        if (!trusted(peer)) return peer;
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.length() > 512 || java.util.Collections.list(request.getHeaders("X-Forwarded-For")).size() != 1) return peer;
        String[] hops = header.split(",", -1);
        if (hops.length > 10) return peer;
        String current = peer;
        for (int i = hops.length - 1; i >= 0 && trusted(current); i--) {
            String hop = hops[i].trim();
            if (!hop.matches("[0-9a-fA-F:.]{2,45}")) return peer;
            try { java.net.InetAddress.getByName(hop); }
            catch (java.net.UnknownHostException ex) { return peer; }
            current = hop;
        }
        return current;
    }
    private boolean trusted(String address) {
        return address != null && trusted.stream().anyMatch(matcher -> matcher.matches(address));
    }
}
