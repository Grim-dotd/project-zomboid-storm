package io.pzstorm.storm.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormHttpServerHostTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(StormHttpServer.HOST_PROPERTY);
    }

    @Test
    void defaultsToLoopback() {
        Assertions.assertEquals("127.0.0.1", StormHttpServer.configuredHost());
    }

    @Test
    void blankFallsBackToLoopback() {
        System.setProperty(StormHttpServer.HOST_PROPERTY, "  ");
        Assertions.assertEquals("127.0.0.1", StormHttpServer.configuredHost());
    }

    @Test
    void honorsOverride() {
        System.setProperty(StormHttpServer.HOST_PROPERTY, " 0.0.0.0 ");
        Assertions.assertEquals("0.0.0.0", StormHttpServer.configuredHost());
    }
}
