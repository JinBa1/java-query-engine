package com.github.jinba1.cuckoodb.server;

import com.github.jinba1.cuckoodb.server.config.CuckooDbProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the cuckooDB REST gateway: a guarded, read-only-by-construction,
 * resource-budgeted HTTP front door onto the in-memory query engine.
 */
@SpringBootApplication
@EnableConfigurationProperties(CuckooDbProperties.class)
public class CuckooDbServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CuckooDbServerApplication.class, args);
    }
}
