package com.demo.booking.model;

/** What we break on purpose during the 2PC demo. */
public enum FailMode {

    /** Everything works. */
    NONE,

    /**
     * All participants vote yes, then the coordinator disappears before writing
     * its decision. The participants are stuck in doubt holding locks and none
     * of them is allowed to decide alone. DDIA figure 9-10.
     */
    CRASH_AFTER_VOTES,

    /**
     * The decision is made and logged, but the commit message to payment never
     * arrives. The coordinator has to keep retrying, forever if necessary.
     */
    COMMIT_LOST_TO_PAYMENT
}
