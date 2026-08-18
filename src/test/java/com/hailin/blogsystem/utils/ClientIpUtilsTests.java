package com.hailin.blogsystem.utils;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpUtilsTests {

    @Test
    void prefersCloudflareConnectingIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.20, 172.16.0.1");
        request.addHeader("X-Real-IP", "192.0.2.30");
        request.setRemoteAddr("127.0.0.1");

        assertThat(ClientIpUtils.getClientIp(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void usesFirstForwardedIpWhenCloudflareHeaderIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.20, 172.16.0.1");
        request.setRemoteAddr("127.0.0.1");

        assertThat(ClientIpUtils.getClientIp(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void fallsBackToRealIpThenRemoteAddress() {
        MockHttpServletRequest realIpRequest = new MockHttpServletRequest();
        realIpRequest.addHeader("X-Real-IP", "192.0.2.30");
        realIpRequest.setRemoteAddr("127.0.0.1");

        MockHttpServletRequest remoteAddressRequest = new MockHttpServletRequest();
        remoteAddressRequest.setRemoteAddr("127.0.0.1");

        assertThat(ClientIpUtils.getClientIp(realIpRequest)).isEqualTo("192.0.2.30");
        assertThat(ClientIpUtils.getClientIp(remoteAddressRequest)).isEqualTo("127.0.0.1");
    }

    @Test
    void skipsBlankAndUnknownHeaderValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "unknown");
        request.addHeader("X-Forwarded-For", " , 198.51.100.20");
        request.setRemoteAddr("127.0.0.1");

        assertThat(ClientIpUtils.getClientIp(request)).isEqualTo("198.51.100.20");
    }
}
