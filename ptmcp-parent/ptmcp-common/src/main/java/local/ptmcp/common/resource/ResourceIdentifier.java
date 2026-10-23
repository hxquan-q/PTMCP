package local.ptmcp.common.resource;

/**
 * Caller-supplied identifier spellings and quote metadata, not a verified or authorized identity.
 * Raw denotes the decoded snapshot/identifier spelling, not a SQL token containing outer quotes or
 * SQL escaping. Normalized is explicitly selected by the dialect producer or binder.
 *
 * <p>Both names contain 1 to 128 Unicode codepoints without NUL or malformed UTF-16. They are
 * preserved exactly: this value does not decode SQL tokens, trim, fold case, normalize Unicode or
 * infer a relationship between the names from the quoted flag. It does not render safe SQL. HTTP
 * adapters must explicitly map domain data rather than directly bind or serialize this record.
 */
public record ResourceIdentifier(String raw, String normalized, boolean quoted) {
    public ResourceIdentifier {
        IdentifierNames.requireValid(raw, "raw");
        IdentifierNames.requireValid(normalized, "normalized");
    }
}
