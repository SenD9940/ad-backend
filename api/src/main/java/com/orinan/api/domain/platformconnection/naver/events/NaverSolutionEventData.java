package com.orinan.api.domain.platformconnection.naver.events;

public record NaverSolutionEventData(String solutionId, String eventId, String changeType,
                                     String accountUid, String accountMappingId) {
    @Override public String toString() { return "NaverSolutionEventData[REDACTED]"; }
}
