package com.demo.booking.model;

public enum FailMode {

    NONE,

    /** All participants vote yes, then the coordinator disappears. DDIA figure 9-10. */
    CRASH_AFTER_VOTES,

    /** The decision is logged but the commit message never reaches payment. */
    COMMIT_LOST_TO_PAYMENT
}
