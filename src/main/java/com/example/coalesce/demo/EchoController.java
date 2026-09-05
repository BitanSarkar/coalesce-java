package com.example.coalesce.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@Tag(name = "Echo")
public class EchoController {

    @Operation(summary = "Echo a query parameter")
    @GetMapping(value = "/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> echo(@RequestParam(defaultValue = "hello") String message) {
        return Mono.just(message);
    }

    @Operation(summary = "Echo the request body")
    @PostMapping(value = "/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> echo(@RequestBody Mono<String> body) {
        return body;
    }
}
