package com.vistay.finance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vistay.contracts.common.v1.AccountRef;
import com.vistay.contracts.common.v1.AccountType;
import com.vistay.contracts.common.v1.DataScopeType;
import com.vistay.contracts.user.v1.GetAuthorizationSnapshotRequest;
import com.vistay.contracts.user.v1.UserAuthorizationServiceGrpc;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FinanceAuthorizationClient {
    private final UserAuthorizationServiceGrpc.UserAuthorizationServiceBlockingStub stub;
    private final Duration deadline;
    private final Duration cacheTtl;
    private final ObjectMapper json;
    private final Map<UUID, CachedSnapshot> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> minimumVersions = new ConcurrentHashMap<>();

    public FinanceAuthorizationClient(
        GrpcChannelFactory channels,
        ObjectMapper json,
        @Value("${vistay.authorization.grpc-deadline:2s}") Duration deadline,
        @Value("${vistay.authorization.local-cache-ttl:30s}") Duration cacheTtl
    ) {
        this.stub = UserAuthorizationServiceGrpc.newBlockingStub(channels.createChannel("user"));
        this.json = json;
        this.deadline = deadline;
        this.cacheTtl = cacheTtl;
    }

    public Snapshot require(UUID accountId, String permission) {
        var snapshot = get(accountId);
        if (!snapshot.active() || snapshot.profileId() == null) {
            throw new AuthorizationException("PROFILE_INACTIVE", "Administrator profile is inactive");
        }
        if (snapshot.scopes(permission).isEmpty()) {
            throw new AuthorizationException("FORBIDDEN_PERMISSION", "Missing permission " + permission);
        }
        return snapshot;
    }

    public Snapshot get(UUID accountId) {
        var now = Instant.now();
        var minimumVersion = minimumVersions.getOrDefault(accountId, 0L);
        var cached = cache.get(accountId);
        if (cached != null && cached.expiresAt().isAfter(now)
            && cached.snapshot().authorizationVersion() >= minimumVersion) {
            return cached.snapshot();
        }
        try {
            var response = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .getAuthorizationSnapshot(GetAuthorizationSnapshotRequest.newBuilder()
                    .setAccount(AccountRef.newBuilder().setAccountId(accountId.toString())
                        .setAccountType(AccountType.ACCOUNT_TYPE_ADMIN).build())
                    .setMinimumAuthorizationVersion(minimumVersion)
                    .build());
            Map<String, Set<Scope>> grants = new ConcurrentHashMap<>();
            Map<String, Set<PartitionBinding>> partitions = new ConcurrentHashMap<>();
            response.getGrantsList().forEach(grant -> {
                Set<Scope> scopes = new LinkedHashSet<>();
                grant.getScopesList().stream()
                    .filter(value -> value != DataScopeType.DATA_SCOPE_TYPE_UNSPECIFIED)
                    .forEach(value -> scopes.add(Scope.valueOf(
                        value.name().substring("DATA_SCOPE_TYPE_".length()))));
                if (grant.getPartitionsCount() > 0) {
                    scopes.add(Scope.PARTITION);
                }
                grants.put(grant.getPermissionCode(), Set.copyOf(scopes));
                partitions.put(grant.getPermissionCode(), grant.getPartitionsList().stream()
                    .map(binding -> new PartitionBinding(binding.getPartitionType(),
                        UUID.fromString(binding.getPartitionId())))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            });
            var snapshot = new Snapshot(response.getActive(),
                response.hasProfileId() ? UUID.fromString(response.getProfileId()) : null,
                response.getAuthorizationVersion(), Map.copyOf(grants), Map.copyOf(partitions));
            cache.put(accountId, new CachedSnapshot(snapshot, now.plus(cacheTtl)));
            return snapshot;
        } catch (StatusRuntimeException unavailable) {
            throw new AuthorizationException("AUTHORIZATION_UNAVAILABLE", "Authorization service is unavailable");
        }
    }

    @KafkaListener(
        topics = "${vistay.topics.authorization-changed:user.authorization-changed.v1}",
        groupId = "${spring.application.name}-authorization-cache"
    )
    public void authorizationChanged(String rawEnvelope) {
        try {
            var data = json.readTree(rawEnvelope).get("data");
            if (data == null || !"ADMIN".equals(data.get("accountType").asText())) return;
            var accountId = UUID.fromString(data.get("accountId").asText());
            minimumVersions.merge(accountId, data.get("authorizationVersion").asLong(), Math::max);
            cache.remove(accountId);
        } catch (Exception invalidEnvelope) {
            throw new IllegalArgumentException("Invalid authorization-changed event", invalidEnvelope);
        }
    }

    public enum Scope { SELF, OWN, ASSIGNED, PARTITION, ALL }
    public record PartitionBinding(String partitionType, UUID partitionId) {}
    public record Snapshot(boolean active, UUID profileId, long authorizationVersion,
        Map<String, Set<Scope>> grants, Map<String, Set<PartitionBinding>> partitions) {
        public Set<Scope> scopes(String permission) { return grants.getOrDefault(permission, Set.of()); }
        public Set<PartitionBinding> partitions(String permission) {
            return partitions.getOrDefault(permission, Set.of());
        }
    }
    public static final class AuthorizationException extends RuntimeException {
        private final String code;
        public AuthorizationException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    private record CachedSnapshot(Snapshot snapshot, Instant expiresAt) {}
}
