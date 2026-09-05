package net.bitsar.coalesce.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The load-comparison demo. Nothing here is published — it exists to exercise the
 * {@code coalesce-spring-boot-starter} on the classpath, which contributes its own beans
 * through auto-configuration rather than through this application's component scan.
 */
@SpringBootApplication
public class CoalesceDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoalesceDemoApplication.class, args);
    }
}
