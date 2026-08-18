package com.hailin.blogsystem;

import com.hailin.blogsystem.service.IpLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IpLocationServiceTests {

    @Autowired
    private IpLocationService ipLocationService;

    @Test
    void identifiesLoopbackAndPrivateAddressesAsLocalNetwork() {
        assertThat(ipLocationService.getLocation("127.0.0.1")).isEqualTo("本地网络");
        assertThat(ipLocationService.getLocation("192.168.1.10")).isEqualTo("本地网络");
        assertThat(ipLocationService.getLocation("::1")).isEqualTo("本地网络");
        assertThat(ipLocationService.getLocation("0:0:0:0:0:0:0:1")).isEqualTo("本地网络");
    }

    @Test
    void returnsProvinceForChineseIp() {
        assertThat(ipLocationService.getLocation("114.114.114.114")).isEqualTo("江苏省");
    }

    @Test
    void returnsChineseCountryNameForOverseasIp() {
        assertThat(ipLocationService.getLocation("8.8.8.8")).isEqualTo("美国");
    }

    @Test
    void returnsUnknownForInvalidOrUnsupportedAddress() {
        assertThat(ipLocationService.getLocation(null)).isEqualTo("未知");
        assertThat(ipLocationService.getLocation("not-an-ip")).isEqualTo("未知");
        assertThat(ipLocationService.getLocation("2001:4860:4860::8888")).isEqualTo("未知");
    }

    @Test
    void usesCloudflareCountryAsFallbackForPublicIpv6() {
        assertThat(ipLocationService.getLocation("2001:4860:4860::8888", "US"))
                .isEqualTo("美国");
        assertThat(ipLocationService.getLocation("2001:4860:4860::8888", "XX"))
                .isEqualTo("未知");
        assertThat(ipLocationService.getLocation("not-an-ip", "US"))
                .isEqualTo("未知");
    }
}
