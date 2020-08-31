package org.bonitasoft.actorfilter.initiator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ActorFilterIT {

    @Container
    private final GenericContainer<?> bonitaContainer =
            new GenericContainer<>("bonita:7.11.1").withExposedPorts(8080);

    private String bonitaHost;
    private Integer bonitaPort;

    @BeforeEach
    public void setUp() {
        bonitaHost = bonitaContainer.getHost();
        bonitaPort = bonitaContainer.getFirstMappedPort();
    }

    @Test
    void bonita_should_be_running() {
        assertThat(bonitaContainer.isRunning()).isTrue();
        assertThat(bonitaHost).isNotBlank();
        assertThat(bonitaPort).isNotZero();
    }
}
