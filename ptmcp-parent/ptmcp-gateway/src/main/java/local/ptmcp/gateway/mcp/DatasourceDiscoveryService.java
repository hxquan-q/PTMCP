package local.ptmcp.gateway.mcp;

import local.ptmcp.persistence.org.AuthorizationReader;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the datasources one caller may discover, closing the agent-side entry gap of the other
 * tools: every usable published datasource that has at least one object visible under the caller's
 * platform/user intersection, evaluated with the same per-object semantics as
 * list_databases_and_tables (DISCOVER platform ceiling minus explicit META denies, otherwise
 * META-or-READ). A datasource with no visible object is never listed; connection coordinates and
 * credentials are never returned. The scan rechecks usability on each loaded snapshot so a
 * datasource pinned between listing and loading fails closed.
 */
final class DatasourceDiscoveryService {
    private final PublishedSnapshotReader snapshots;
    private final AuthorizationReader authorization;
    private final ToolPermissionService permissions;

    DatasourceDiscoveryService(
            PublishedSnapshotReader snapshots,
            AuthorizationReader authorization,
            ToolPermissionService permissions) {
        this.snapshots = snapshots;
        this.authorization = authorization;
        this.permissions = permissions;
    }

    /** One discoverable connection; identifiers are administrative data, never instructions. */
    record VisibleDatasource(
            long datasourceId,
            String code,
            String name,
            String engine,
            String databaseName,
            boolean queryable) {}

    /**
     * Visible datasources in id order. {@code queryable} marks whether READ is granted on any
     * object, so an agent can tell browse-only connections from queryable ones before calling
     * execute_readonly_sql.
     */
    List<VisibleDatasource> listVisible(long userId, long platformId) {
        AuthorizationReader.PlatformInfo platform = authorization.loadPlatform(platformId);
        boolean discover = "DISCOVER".equals(platform.catalogMode());
        List<VisibleDatasource> visible = new ArrayList<>();
        for (PublishedSnapshotReader.UsableDatasource usable : snapshots.listUsable()) {
            PublishedSnapshotReader.Published published = snapshots.load(usable.datasourceId());
            if (!published.usable()) {
                continue;
            }
            ToolPermissionService.Session session =
                    permissions.open(userId, platformId, usable.datasourceId(), published);
            boolean anyVisible = false;
            boolean anyReadable = false;
            scan:
            for (PublishedSnapshotReader.Namespace namespace : published.namespaces()) {
                for (PublishedSnapshotReader.TableObject object : namespace.objects()) {
                    boolean visibleObject =
                            discover
                                    ? session.platformVisible(object.id())
                                            && !session.explicitMetaDeny(object.id())
                                    : session.decide(object.id()).meta()
                                            || session.decide(object.id()).read();
                    anyVisible |= visibleObject;
                    anyReadable |= session.decide(object.id()).read();
                    if (anyVisible && anyReadable) {
                        break scan;
                    }
                }
            }
            if (anyVisible) {
                visible.add(
                        new VisibleDatasource(
                                usable.datasourceId(),
                                usable.code(),
                                usable.name(),
                                usable.engine(),
                                usable.databaseName(),
                                anyReadable));
            }
        }
        return List.copyOf(visible);
    }
}
