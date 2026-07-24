package com.cloudmind.demo.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiEndpointSecurityServiceTest {
    @Test
    void acceptsAllowlistedHttpsHostResolvingToPublicAddress() {
        AiEndpointSecurityService service = new AiEndpointSecurityService(
                "api.example.com",
                ignored -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}
        );

        assertEquals(
                "https://api.example.com/v1",
                service.validateBaseUrl("https://api.example.com/v1/").toString()
        );
    }

    @Test
    void rejectsHttpUnknownHostsAndNonStandardPorts() {
        AiEndpointSecurityService service = new AiEndpointSecurityService(
                "api.example.com",
                ignored -> new InetAddress[]{InetAddress.getByName("8.8.8.8")}
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateBaseUrl("http://api.example.com/v1")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateBaseUrl("https://other.example.com/v1")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateBaseUrl("https://api.example.com:8443/v1")
        );
    }

    @Test
    void rejectsHostResolvingToPrivateOrReservedAddress() {
        AiEndpointSecurityService privateService = new AiEndpointSecurityService(
                "api.example.com",
                ignored -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}
        );
        AiEndpointSecurityService reservedService = new AiEndpointSecurityService(
                "api.example.com",
                ignored -> new InetAddress[]{InetAddress.getByName("192.0.2.10")}
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> privateService.validateBaseUrl("https://api.example.com/v1")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> reservedService.validateBaseUrl("https://api.example.com/v1")
        );
    }
}
