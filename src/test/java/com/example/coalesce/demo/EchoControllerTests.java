package com.example.coalesce.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EchoControllerTests {

    @LocalServerPort
    int port;

    @Test
    void getEchoesQueryParam() {
        client().get().uri("/echo?message=ping")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ping");
    }

    @Test
    void postEchoesBody() {
        client().post().uri("/echo")
                .bodyValue("pong")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("pong");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
