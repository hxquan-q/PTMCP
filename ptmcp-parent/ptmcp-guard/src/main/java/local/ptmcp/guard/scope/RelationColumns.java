package local.ptmcp.guard.scope;

import local.ptmcp.common.resource.ResourceRef;

import java.util.List;

/** Server-side column snapshot lookup; the guard never opens a business connection. */
@FunctionalInterface
public interface RelationColumns {
    List<ColumnDef> columns(ResourceRef relation);
}
