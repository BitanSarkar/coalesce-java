package com.example.coalesce.demo;

import java.time.Instant;

public record OrderDto(String orderId, String customer, String status, long amountCents, Instant placedAt) {
}
