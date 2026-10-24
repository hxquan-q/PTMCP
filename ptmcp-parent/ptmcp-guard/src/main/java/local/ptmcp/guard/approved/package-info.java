/**
 * Closed gate pipeline end: pipeline inputs, the permission callback and the package-private {@link
 * local.ptmcp.guard.approved.ApprovedQuery}. An {@code ApprovedQuery} can only be produced by a
 * successful {@link local.ptmcp.guard.approved.GuardPipeline#guard} run inside this package; there
 * is no public constructor, no deserialization entry point and no executor here. Holding an
 * instance authorizes nothing until the executor re-verifies subject, versions, hash and expiry.
 */
package local.ptmcp.guard.approved;
