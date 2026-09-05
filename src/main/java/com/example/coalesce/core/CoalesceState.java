package com.example.coalesce.core;



public record CoalesceState(Status status, byte[] payload, String errorMessage, long computedAt) {

    public enum Status { DONE, FAILED, PENDING, ABSENT }

    /** For a freshly computed result — stamps "now" as the computation time. */
    public static CoalesceState done(byte[] payload) {
        return new CoalesceState(Status.DONE, payload, null, System.currentTimeMillis());
    }

    /**
     * For a result read back out of Redis. The timestamp MUST come from the stored
     * envelope, not from the clock at read time — otherwise every cached value looks
     * freshly computed and stale-while-revalidate never fires.
     */
    public static CoalesceState done(byte[] payload, long computedAt) {
        return new CoalesceState(Status.DONE, payload, null, computedAt);
    }

    public static CoalesceState failed(String message) {
        return new CoalesceState(Status.FAILED, null, message, System.currentTimeMillis());
    }

    public static CoalesceState failed(String message, long computedAt) {
        return new CoalesceState(Status.FAILED, null, message, computedAt);
    }

    public static CoalesceState absent() {
        return new CoalesceState(Status.ABSENT, null, null, 0);
    }
}
