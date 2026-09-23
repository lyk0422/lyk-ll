package com.example.starter.rosbridge.store;

/** Action goal 行。 */
public record ActionGoalRow(String clientId, String goalId, String actionName,
                            String status, String resultJson,
                            long createdAt, Long completedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String CANCELED = "CANCELED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String ABORTED = "ABORTED";

    public boolean terminal() {
        return !ACTIVE.equals(status);
    }
}
