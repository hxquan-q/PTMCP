package local.ptmcp.admin.api;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.IdempotencyRecorder;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;

/**
 * Idempotency-Key wrapper for admin POST actions. A replay returns the recorded status and body
 * with {@code Idempotent-Replay: true}; a failed attempt releases its lease so the caller can retry
 * with the same key.
 */
final class IdempotentWrites {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_KEY = 128;

    private IdempotentWrites() {}

    static <T> ResponseEntity<byte[]> execute(
            IdempotencyRecorder recorder,
            long actorId,
            String routeKey,
            String idempotencyKey,
            byte[] requestBody,
            Supplier<ResponseEntity<T>> action) {
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_KEY) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        byte[] hash = sha256(requestBody);
        IdempotencyRecorder.OccupyResult occupied =
                recorder.occupy(actorId, routeKey, idempotencyKey, hash);
        if (occupied.state() == IdempotencyRecorder.State.REPLAY) {
            return ResponseEntity.status(occupied.replay().httpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotent-Replay", "true")
                    .body(occupied.replay().responseBody());
        }
        ResponseEntity<T> response;
        try {
            response = action.get();
        } catch (RuntimeException failure) {
            release(recorder, actorId, routeKey, idempotencyKey);
            throw failure;
        }
        byte[] body =
                response.getBody() == null
                        ? new byte[0]
                        : JSON.writeValueAsString(response.getBody())
                                .getBytes(StandardCharsets.UTF_8);
        recorder.completeSucceeded(
                actorId, routeKey, idempotencyKey, body, response.getStatusCode().value());
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(body);
    }

    /** Optimistic-concurrency headers are part of the request intent for recovery actions. */
    static <T> ResponseEntity<byte[]> execute(
            IdempotencyRecorder recorder,
            long actorId,
            String routeKey,
            String key,
            long expectedVersion,
            byte[] requestBody,
            Supplier<ResponseEntity<T>> action) {
        byte[] body = requestBody == null ? new byte[0] : requestBody;
        byte[] intent =
                java.nio.ByteBuffer.allocate(Long.BYTES + body.length)
                        .putLong(expectedVersion)
                        .put(body)
                        .array();
        return execute(recorder, actorId, routeKey, key, intent, action);
    }

    private static void release(
            IdempotencyRecorder recorder, long actorId, String routeKey, String key) {
        try {
            recorder.completeFailed(actorId, routeKey, key);
        } catch (RuntimeException ignored) {
            // Preserve the original failure; the lease expires on its own.
        }
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body);
        } catch (NoSuchAlgorithmException error) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
