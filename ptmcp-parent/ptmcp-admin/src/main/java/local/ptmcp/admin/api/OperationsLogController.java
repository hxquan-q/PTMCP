package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.operations.OperationsLogService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/admin/operations: the durable admin operations trail (delegations, platforms,
 * clients, identity changes) written by the management slices. Platform-admin only; the masked
 * before/after payloads are shown as stored — this read never unmasks anything.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/operations")
public class OperationsLogController {
    private final OperationsLogService operations;

    public OperationsLogController(OperationsLogService operations) {
        this.operations = operations;
    }

    public record OperationsRow(
            long id,
            long actorId,
            String requestId,
            String action,
            String aggregateType,
            String aggregateId,
            String beforeMasked,
            String afterMasked,
            Instant occurredAt) {}

    public record OperationsPage(List<OperationsRow> items, String nextCursor) {}

    @GetMapping
    public OperationsPage list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        OperationsLogService.OperationsPage page =
                operations.list(action, aggregateType, actorId, cursor, pageSize);
        List<OperationsRow> items =
                page.items().stream()
                        .map(
                                row ->
                                        new OperationsRow(
                                                row.id(),
                                                row.actorId(),
                                                row.requestId(),
                                                row.action(),
                                                row.aggregateType(),
                                                row.aggregateId(),
                                                row.beforeMasked(),
                                                row.afterMasked(),
                                                row.occurredAt()))
                        .toList();
        return new OperationsPage(items, page.nextCursor());
    }
}
