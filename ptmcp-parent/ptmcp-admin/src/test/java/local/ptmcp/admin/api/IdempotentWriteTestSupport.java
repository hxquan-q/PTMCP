package local.ptmcp.admin.api;

import local.ptmcp.persistence.org.IdempotencyRecorder;

import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

/** Test bridge to exercise response failures against AdminApiTest's owned MySQL instance. */
public final class IdempotentWriteTestSupport {
    private IdempotentWriteTestSupport() {}

    public static <T> ResponseEntity<byte[]> execute(
            IdempotencyRecorder recorder,
            long actorId,
            String route,
            String key,
            byte[] body,
            Supplier<ResponseEntity<T>> action) {
        return IdempotentWrites.execute(recorder, actorId, route, key, body, action);
    }
}
