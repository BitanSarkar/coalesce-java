package com.acme.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lives in com.acme.app -- nothing here scans net.bitsar.coalesce. If @Coalesce works,
 * it worked purely through the starter's auto-configuration.
 */
@SpringBootApplication
public class ConsumerApp {
}
