package com.example.coalesce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lives in {@code com.example} so component scanning reaches every sibling package —
 * {@code com.example.coalesce} (the framework) and {@code com.example.orders} (the demo).
 */
@SpringBootApplication
public class CoalescePocApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoalescePocApplication.class, args);
    }
}
