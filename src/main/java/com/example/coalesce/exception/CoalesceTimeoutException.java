package com.example.coalesce.exception;



/** A follower waited past {@code waitTimeoutSeconds} without the leader producing an outcome. */
public class CoalesceTimeoutException extends RuntimeException {

    private final String key;

    public CoalesceTimeoutException(String key) {
        super("Timed out waiting for coalesced result: " + key);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
