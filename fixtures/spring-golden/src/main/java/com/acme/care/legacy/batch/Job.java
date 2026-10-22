package com.acme.care.legacy.batch;

/**
 * Unit of work for the legacy nightly batch runner. Jobs run sequentially, in registration
 * order, on the runner's single thread.
 */
public interface Job {

    String name();

    /**
     * @throws IllegalStateException to abort the remaining jobs of the current run
     */
    void execute(JobContext context);
}
