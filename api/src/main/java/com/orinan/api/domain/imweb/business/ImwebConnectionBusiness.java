package com.orinan.api.domain.imweb.business;

import com.orinan.api.annotation.Business;
import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import com.orinan.api.common.time.SeoulDateTimes;
import com.orinan.api.domain.imweb.client.ImwebApiClient;
import com.orinan.api.domain.imweb.client.ImwebProperties;
import com.orinan.api.domain.imweb.service.*;
import com.orinan.api.domain.imweb.service.ImwebAccessService.*;
import com.orinan.api.domain.platformconnection.controller.model.PlatformConnectionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

@Business @RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ImwebConnectionBusiness {
    private final ImwebAccessService access;
    private final ImwebApiClient client;
    private final ImwebProperties properties;
    private final ImwebOAuthStateService states;
    private final ImwebRefreshLease leases;

    public Capabilities capabilities(Long workspaceId, Long userId) {
        access.requireMember(workspaceId, userId);
        return new Capabilities(properties.isConfigured(), properties.isConfigured() ? null : "아임웹 앱의 OAuth 연결 설정이 필요합니다. 관리자에게 문의해 주세요.");
    }
    public Authorization authorize(Long workspaceId, Long userId, String siteCode) {
        access.requireOwner(workspaceId, userId); properties.validate();
        if (!ImwebOAuthStateService.validSite(siteCode)) throw bad("아임웹 사이트 코드를 확인해 주세요.");
        String state = states.issue(workspaceId, userId, siteCode);
        String url = UriComponentsBuilder.fromUriString("https://openapi.imweb.me/oauth2/authorize")
                .queryParam("responseType", "code").queryParam("clientId", properties.getClientId())
                .queryParam("redirectUri", properties.getRedirectUri()).queryParam("scope", properties.getScopes())
                .queryParam("siteCode", siteCode).queryParam("state", state).build().encode().toUriString();
        return new Authorization(state, url);
    }
    public PlatformConnectionResponse complete(String state, String browserState, String code, String error) {
        properties.validate();
        var owner = states.consume(state, browserState);
        if (error != null || code == null || code.isBlank() || code.length() > 4096) throw bad("아임웹 연결이 취소되었거나 인증 코드가 없습니다.");
        access.requireOwner(owner.workspaceId(), owner.userId());
        var token = client.exchangeCode(code); requireScopes(token.scopes());
        access.requireOwner(owner.workspaceId(), owner.userId());
        try { client.completeIntegration(token.accessToken()); }
        catch (ImwebApiClient.IntegrationStateException alreadyCompleted) { /* Verify access and exact site below. */ }
        access.requireOwner(owner.workspaceId(), owner.userId());
        var site = client.site(token.accessToken());
        verifySite(site, owner.siteCode());
        var units = site.path("unitList");
        String name = units.isArray() && !units.isEmpty() ? text(units.get(0), "name", 255) : null;
        return access.save(owner.workspaceId(), owner.userId(), owner.siteCode(), name == null ? owner.siteCode() : name, token);
    }
    public List<Store> stores(Long workspaceId, Long userId) { return access.stores(workspaceId, userId); }
    public List<Unit> units(Long workspaceId, Long connectionId, Long userId) {
        return fetchUnits(workspaceId, connectionId, userId).units();
    }
    public List<Store> selectUnits(Long workspaceId, Long connectionId, Long userId, List<String> unitCodes) {
        if (unitCodes == null || unitCodes.isEmpty() || unitCodes.size() > 100
                || unitCodes.stream().anyMatch(c -> !ImwebOAuthStateService.validUnit(c))) throw bad("저장할 아임웹 스토어를 선택해 주세요.");
        var fetched = fetchUnits(workspaceId, connectionId, userId);
        var selected = unitCodes.stream().distinct().map(code -> fetched.units().stream().filter(u -> u.unitCode().equals(code))
                .findFirst().orElseThrow(() -> bad("선택한 아임웹 스토어에 접근할 수 없습니다. 목록을 다시 조회해 주세요."))).toList();
        return access.saveUnits(workspaceId, userId, fetched.credentials(), selected);
    }
    public Context context(Long workspaceId, Long assetId, Long userId) {
        var store = access.store(workspaceId, assetId, userId);
        if (store.requiresReauth()) throw bad("아임웹 사이트를 다시 연결해 주세요.");
        var verified = authenticatedSite(workspaceId, store.connectionId(), userId);
        var credentials = verified.credentials(); var site = verified.site(); verifySite(site, store.siteCode());
        if (!unitCodes(site).contains(store.unitCode())) throw bad("저장한 아임웹 스토어에 접근할 수 없습니다. 자산 편집에서 다시 확인해 주세요.");
        var unit = parseUnit(client.unit(credentials.accessToken(), store.unitCode()), store.siteCode(), store.unitCode(), true);
        if (!Objects.equals(unit.currency(), store.currency())) throw bad("아임웹 스토어 통화가 변경되었습니다. 자산을 다시 저장해 주세요.");
        var context = new Context(workspaceId, userId, store.connectionId(), assetId, store.siteCode(), store.unitCode(),
                store.currency(), credentials.accessToken(), credentials.credentialVersion());
        access.requireUnchanged(context); return context;
    }
    /** Caller may retry a read once; never use this to repeat an external write. */
    public Context refresh(Context expected) {
        access.requireUnchanged(expected);
        renew(expected.workspaceId(), expected.connectionId(), expected.userId(), expected.credentialVersion());
        return context(expected.workspaceId(), expected.assetId(), expected.userId());
    }
    private Fetched fetchUnits(Long workspaceId, Long connectionId, Long userId) {
        var verified = authenticatedSite(workspaceId, connectionId, userId);
        var credentials = verified.credentials(); var site = verified.site(); verifySite(site, credentials.siteCode());
        var saved = access.stores(workspaceId, userId).stream().filter(s -> s.connectionId().equals(connectionId)).map(Store::unitCode).toList();
        var result = new ArrayList<Unit>();
        for (String code : unitCodes(site)) {
            access.requireUnchanged(workspaceId, userId, credentials);
            result.add(parseUnit(client.unit(credentials.accessToken(), code), credentials.siteCode(), code, saved.contains(code)));
        }
        access.requireUnchanged(workspaceId, userId, credentials);
        return new Fetched(credentials, List.copyOf(result));
    }
    private Credentials current(Long workspaceId, Long connectionId, Long userId) {
        return renew(workspaceId, connectionId, userId, null);
    }
    private SiteCredentials authenticatedSite(Long workspaceId, Long connectionId, Long userId) {
        var credentials = current(workspaceId, connectionId, userId);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var site = client.site(credentials.accessToken());
                access.requireUnchanged(workspaceId, userId, credentials);
                return new SiteCredentials(credentials, site);
            } catch (ImwebApiClient.AuthenticationException authentication) {
                if (attempt == 1) { access.requireReauth(workspaceId, userId, credentials); throw authentication; }
                credentials = renew(workspaceId, connectionId, userId, credentials.credentialVersion());
            }
        }
        throw new IllegalStateException("Unreachable");
    }
    private Credentials renew(Long workspaceId, Long connectionId, Long userId, Long forceVersion) {
        properties.validate();
        var credentials = access.credentials(workspaceId, connectionId, userId);
        if (fresh(credentials) && (forceVersion == null || forceVersion != credentials.credentialVersion())) return credentials;
        String lease = leases.acquire(connectionId);
        Credentials expected = credentials;
        boolean attempted = false;
        try {
            // Another completed renewal may have saved a rotated token before this lease was acquired.
            expected = access.credentials(workspaceId, connectionId, userId);
            if (fresh(expected) && (forceVersion == null || forceVersion != expected.credentialVersion())) return expected;
            leases.requireOwned(connectionId, lease);
            attempted = true;
            var token = client.refreshToken(expected.refreshToken()); requireScopes(token.scopes());
            // Refresh rotation can succeed even when a later read fails. Such failures require reconnect, never replay.
            verifySite(client.site(token.accessToken()), expected.siteCode());
            access.requireUnchanged(workspaceId, userId, expected); leases.requireOwned(connectionId, lease);
            return access.replaceToken(workspaceId, userId, expected, token);
        } catch (RuntimeException failure) {
            if (attempted) {
                try { access.requireReauth(workspaceId, userId, expected); } catch (RuntimeException ignored) { /* Permission/rebind won the race. */ }
            }
            throw failure;
        } finally { leases.release(connectionId, lease); }
    }
    private boolean fresh(Credentials c) { return c.expiresAt() != null && c.expiresAt().isAfter(SeoulDateTimes.now().plusMinutes(2)); }
    private void requireScopes(String scopes) {
        if (scopes != null && !new HashSet<>(Arrays.asList(scopes.split("\\s+"))).containsAll(Arrays.asList(ImwebProperties.SCOPES.split(" "))))
            throw bad("아임웹 사이트, 상품 및 주문 조회·상품 등록 권한을 허용한 뒤 다시 연결해 주세요.");
    }
    private void verifySite(JsonNode site, String expected) {
        if (!expected.equals(text(site, "siteCode", 100))) throw bad("요청한 아임웹 사이트와 인증된 사이트가 일치하지 않습니다. 다시 연결해 주세요.");
        unitCodes(site);
    }
    private List<String> unitCodes(JsonNode site) {
        JsonNode units = site.path("unitList");
        if (!units.isArray() || units.size() > 100) throw responseError();
        var result = new LinkedHashSet<String>();
        for (var unit : units) {
            String code = text(unit, "unitCode", 100);
            if (!ImwebOAuthStateService.validUnit(code) || !result.add(code)) throw responseError();
        }
        return List.copyOf(result);
    }
    private Unit parseUnit(JsonNode unit, String siteCode, String unitCode, boolean selected) {
        if (!siteCode.equals(text(unit, "siteCode", 100)) || !unitCode.equals(text(unit, "unitCode", 100))) throw responseError();
        String currency = text(unit, "currency", 3), name = text(unit, "name", 255);
        if (currency == null || !currency.matches("[A-Z]{3}")) throw responseError();
        String domain = text(unit, "primaryDomain", 2048);
        return new Unit(unitCode, name == null ? unitCode : name, currency, storeUrl(domain), selected);
    }
    private String storeUrl(String domain) {
        if (domain == null) return null;
        try {
            URI uri = URI.create(domain.contains("://") ? domain : "https://" + domain);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && uri.getQuery() == null && uri.getPort() == -1 ? uri.toASCIIString() : null;
        } catch (IllegalArgumentException e) { return null; }
    }
    private String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() && value.asString().length() <= max ? value.asString().strip() : null;
    }
    private ApiException responseError() { return bad("아임웹 스토어 응답을 확인할 수 없습니다. 잠시 후 다시 조회해 주세요."); }
    private ApiException bad(String message) { return new ApiException(ApiCode.BAD_REQUEST, message); }
    public record Capabilities(boolean enabled, String disabledReason) {}
    public record Authorization(String state, String authorizationUrl) { @Override public String toString() { return "ImwebAuthorization[REDACTED]"; } }
    private record Fetched(Credentials credentials, List<Unit> units) {
        @Override public String toString() { return "ImwebUnits[REDACTED]"; }
    }
    private record SiteCredentials(Credentials credentials, JsonNode site) {
        @Override public String toString() { return "ImwebSiteCredentials[REDACTED]"; }
    }
}
