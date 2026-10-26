package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.platform.PlatformDirectoryService;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/admin/platforms. Read-only directory projection today: the console list and filter
 * sources. Mutation (create/update/delete/scope) belongs to the platform console slice and is
 * intentionally absent so no write path exists without its own contract and tests.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/platforms")
public class PlatformAdminController {
    private final PlatformDirectoryService platforms;

    public PlatformAdminController(PlatformDirectoryService platforms) {
        this.platforms = platforms;
    }

    public record PageView(
            java.util.List<PlatformDirectoryService.PlatformView> items, String nextCursor) {}

    @GetMapping
    public ResponseEntity<PageView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        PlatformDirectoryService.Page page =
                platforms.list(keyword, status, parseCursor(cursor), pageSize);
        return ResponseEntity.ok(new PageView(page.items(), page.nextCursor()));
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor);
            if (value <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return value;
        } catch (NumberFormatException error) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
