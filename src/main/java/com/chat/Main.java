package com.chat;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new ChatVerticle())
                .onSuccess(id -> log.info("ChatVerticle deployed: {}", id))
                .onFailure(err -> {
                    log.error("Failed to deploy ChatVerticle", err);
                    vertx.close();
                });
    }
}
