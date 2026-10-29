package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** A locked authority snapshot. Call only after acquiring the global security row lock. */
final class DepartmentTree {
    private static final int MAX_PATH_NODES = 64;
    private static final int MAX_CACHE_CHARS = 500;

    private final Map<Long, Node> nodes;
    private final Map<Long, List<Node>> children = new LinkedHashMap<>();

    private DepartmentTree(Map<Long, Node> nodes) {
        this.nodes = nodes;
        for (Node node : nodes.values()) {
            Long parentId = node.row().parentId();
            if (parentId != null) {
                children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(node);
            }
        }
    }

    static DepartmentTree lock(Connection connection) throws SQLException {
        Map<Long, Node> rows = new LinkedHashMap<>();
        try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT dept_id,parent_id,dept_name,status,version,ancestors,del_flag"
                                        + " FROM sys_dept ORDER BY dept_id FOR UPDATE");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long id = result.getLong(1);
                long parentId = result.getLong(2);
                rows.put(
                        id,
                        new Node(
                                new OrgStore.DepartmentRow(
                                        id,
                                        parentId == 0 ? null : parentId,
                                        result.getString(3),
                                        "0".equals(result.getString(4)),
                                        result.getLong(5),
                                        result.getString(6)),
                                !"0".equals(result.getString(7))));
            }
        }
        return new DepartmentTree(rows);
    }

    OrgStore.DepartmentRow requireActive(long id) {
        Node node = nodes.get(id);
        if (node == null || node.deleted()) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return node.row();
    }

    /** Root first, including the target and deleted/disabled bridges. Cache is never consulted. */
    List<Long> path(long id) {
        List<Long> path = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Long cursor = id;
        while (cursor != null) {
            if (cursor <= 0 || path.size() == MAX_PATH_NODES || !seen.add(cursor)) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            Node node = nodes.get(cursor);
            if (node == null) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            path.add(cursor);
            cursor = node.row().parentId();
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    String createAncestors(Long parentId) {
        return proposedCache(parentPath(parentId));
    }

    Mutation planChange(OrgStore.DepartmentRow self, Long parentId) {
        List<Long> currentPath = path(self.id());
        nextVersion(self.version());
        boolean moved = !Objects.equals(self.parentId(), parentId);
        if (moved) {
            validateDescendants(self.id());
        }
        List<Long> parents = moved
                ? parentPath(parentId)
                : currentPath.subList(0, currentPath.size() - 1);
        if (parents.contains(self.id())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String ancestors = proposedCache(parents);
        if (!moved) {
            return new Mutation(ancestors, List.of());
        }

        List<CacheUpdate> descendants = new ArrayList<>();
        ArrayDeque<PlannedNode> pending = new ArrayDeque<>();
        List<Long> selfPath = append(parents, self.id());
        enqueueChildren(pending, self.id(), selfPath);
        while (!pending.isEmpty()) {
            PlannedNode planned = pending.removeFirst();
            OrgStore.DepartmentRow row = planned.node().row();
            descendants.add(
                    new CacheUpdate(row.id(), row.version(), proposedCache(planned.parents())));
            enqueueChildren(pending, row.id(), append(planned.parents(), row.id()));
        }
        return new Mutation(ancestors, List.copyOf(descendants));
    }

    /** Validate all existing rows before checking any proposed depth or cache length. */
    private void validateDescendants(long id) {
        Set<Long> visited = new HashSet<>();
        visited.add(id);
        ArrayDeque<Node> pending = new ArrayDeque<>(children.getOrDefault(id, List.of()));
        while (!pending.isEmpty()) {
            OrgStore.DepartmentRow row = pending.removeFirst().row();
            if (!visited.add(row.id())) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            path(row.id());
            nextVersion(row.version());
            pending.addAll(children.getOrDefault(row.id(), List.of()));
        }
    }

    private List<Long> parentPath(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        requireActive(parentId);
        return path(parentId);
    }

    private void enqueueChildren(ArrayDeque<PlannedNode> pending, long id, List<Long> parents) {
        for (Node child : children.getOrDefault(id, List.of())) {
            pending.addLast(new PlannedNode(child, parents));
        }
    }

    private static List<Long> append(List<Long> path, long id) {
        List<Long> appended = new ArrayList<>(path);
        appended.add(id);
        return List.copyOf(appended);
    }

    private static String proposedCache(List<Long> parents) {
        if (parents.size() >= MAX_PATH_NODES) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String cache = parents.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (cache.length() > MAX_CACHE_CHARS) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return cache;
    }

    static long nextVersion(long version) {
        if (version <= 0 || version == Long.MAX_VALUE) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        return version + 1;
    }

    private record Node(OrgStore.DepartmentRow row, boolean deleted) {}

    private record PlannedNode(Node node, List<Long> parents) {}

    record CacheUpdate(long id, long expectedVersion, String ancestors) {}

    record Mutation(String ancestors, List<CacheUpdate> descendants) {}
}
