package net.bitsar.coalesce.exception;



/** Payload could not be encoded to, or decoded from, its cached wire format. */
public class CoalesceCodecException extends RuntimeException {

    public CoalesceCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
