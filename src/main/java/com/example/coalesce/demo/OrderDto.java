package com.example.coalesce.demo;

import java.time.Instant;

public record OrderDto(String orderId, String status, long amountCents, Instant fetchedAt) {
}
