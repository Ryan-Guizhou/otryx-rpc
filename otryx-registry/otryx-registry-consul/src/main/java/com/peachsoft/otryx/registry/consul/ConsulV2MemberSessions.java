package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.api.ApplicationInstance;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用原生 Consul Session 与 KV Acquire 提供真正跨进程互斥的应用实例所有权。
 *
 * <p>一个 ApplicationKey+instanceId 仅有一个活跃 Session Claim。Session TTL
 * 到期后 Consul 删除锁键；新进程方可取得身份。普通 Mapping Owner 的单调
 * Generation 墓碑与这里的临时 Session 锁独立，不能因会话失效被误删。
 *
 * <p>调用均为阻塞 API，只能在有界 Consul 控制面 Executor 中执行。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:19
 */
final class ConsulV2MemberSessions {

    private static final String METHOD_PUT = "PUT";
    private static final String PATH_CREATE = "/v1/session/create";
    private static final String PATH_RENEW = "/v1/session/renew/";
    private static final String PATH_DESTROY = "/v1/session/destroy/";
    private static final String FIELD_ID = "ID";
    private static final String FIELD_NAME = "Name";
    private static final String FIELD_BEHAVIOR = "Behavior";
    private static final String FIELD_TTL = "TTL";
    private static final String FIELD_LOCK_DELAY = "LockDelay";
    private static final String SESSION_NAME = "otryx-v2-application-member";
    private static final String BEHAVIOR_DELETE = "delete";
    private static final String LOCK_DELAY_ZERO = "0s";
    private static final String SECONDS_SUFFIX = "s";

    private final ConsulV2Client client;
    private final ConsulV2Identity identity;
    private final long ttlSeconds;

    ConsulV2MemberSessions(ConsulV2Client client, ConsulV2Identity identity, long ttlSeconds) {
        this.client = Objects.requireNonNull(client, "client");
        this.identity = Objects.requireNonNull(identity, "identity");
        if (ttlSeconds < 10L || ttlSeconds > 86400L) {
            throw new IllegalArgumentException("Invalid Consul Session TTL");
        }
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 创建真实 TTL Session 后对 ApplicationInstance 声明互斥所有权。
     *
     * @return 当前实例唯一持有的 Session UUID
     */
    String createAndAcquire(ApplicationInstance member, String token) throws Exception {
        identity.requireNamespace(member.application());
        Objects.requireNonNull(token, "token");
        ConsulV2Client.JsonResult response = client.json(METHOD_PUT, PATH_CREATE, Map.of(
                FIELD_NAME, SESSION_NAME,
                FIELD_BEHAVIOR, BEHAVIOR_DELETE,
                FIELD_TTL, ttlSeconds + SECONDS_SUFFIX,
                FIELD_LOCK_DELAY, LOCK_DELAY_ZERO));
        String session = response.body() == null ? "" : response.body().path(FIELD_ID).asText("");
        if (!validSession(session)) {
            throw new IllegalStateException("Consul Session creation returned an invalid ID");
        }
        try {
            String key = identity.memberClaimKey(member.application(), member.instanceId());
            if (!client.acquire(key, session, token.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalStateException("Consul application instance is owned by another session");
            }
            if (!owns(member, session, token)) {
                throw new IllegalStateException("Consul Session claim was not durable");
            }
            return session;
        } catch (Exception failure) {
            try {
                destroy(session);
            } catch (Exception cleanupError) {
                failure.addSuppressed(cleanupError);
            }
            throw failure;
        }
    }

    /**
     * 必须同时具有有效 Session 与当前 KV Session Owner，才能继续刷新 Agent TTL。
     *
     * @return Session 仍存活且实例 Claim 的 value/session 均匹配时为 true
     */
    boolean renewAndVerify(ApplicationInstance member, String session, String token) throws Exception {
        if (!validSession(session)) {
            return false;
        }
        ConsulV2Client.JsonResult renewed = client.json(
                METHOD_PUT, PATH_RENEW + session, null);
        if (renewed.missing() || renewed.body() == null || !renewed.body().isArray()
                || renewed.body().size() != 1) {
            return false;
        }
        return owns(member, session, token);
    }

    /** 通过 Consul KV Session ID 与进程 Token 验证实例归属，而非信任 Service Meta。 */
    boolean owns(ApplicationInstance member, String session, String token) throws Exception {
        identity.requireNamespace(member.application());
        String key = identity.memberClaimKey(member.application(), member.instanceId());
        return client.get(key).map(entry -> session.equals(entry.session())
                && Arrays.equals(entry.value(), token.getBytes(StandardCharsets.UTF_8))).orElse(false);
    }

    /** 销毁 Session 触发 Behavior=delete，仅移除会话拥有的临时 Claim 键。 */
    void destroy(String session) throws Exception {
        if (!validSession(session)) {
            return;
        }
        ConsulV2Client.JsonResult response = client.json(METHOD_PUT, PATH_DESTROY + session, null);
        if (response.missing()) {
            throw new IllegalStateException("Consul Session destruction API is unavailable");
        }
    }

    private static boolean validSession(String session) {
        if (session == null) {
            return false;
        }
        try {
            return UUID.fromString(session).toString().equalsIgnoreCase(session);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
