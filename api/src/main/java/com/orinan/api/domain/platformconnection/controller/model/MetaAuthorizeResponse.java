package com.orinan.api.domain.platformconnection.controller.model;

public record MetaAuthorizeResponse(String authorizationUrl, long expiresInSeconds) {
}
