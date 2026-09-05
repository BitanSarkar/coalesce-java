package com.example.coalesce.demo;

/** Which path a request took, so the two can be compared side by side. */
public enum Mode {

    /** Straight through to the simulated downstream — no coalescing, no cache. */
    DIRECT,

    /** Through @Coalesce. */
    COALESCED
}
