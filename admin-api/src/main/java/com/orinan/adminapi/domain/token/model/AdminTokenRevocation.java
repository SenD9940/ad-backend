package com.orinan.adminapi.domain.token.model;

/** Audit-safe result: contains session versions and a count, never token values. */
public record AdminTokenRevocation(long previousAuthVersion, long authVersion, int revokedSessions) {}
