package net.bitsar.coalesce.demo;

/** The simulated downstream landed in the slow tail and gave up. */
public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(String message) {
        super(message);
    }
}
