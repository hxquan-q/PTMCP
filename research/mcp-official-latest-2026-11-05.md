# MCP Official Latest Research — 2026-11-05

## Executive Summary

The current official MCP specification shown by modelcontextprotocol.io is revision **2026-07-28**, explicitly labeled “latest” on the specification page. The official GitHub organization is `modelcontextprotocol`; the specification/documentation repository is `modelcontextprotocol/modelcontextprotocol`. The 2026-07-28 revision makes MCP substantially stateless: it removes protocol-level sessions, `Mcp-Session-Id`, the initialization handshake, SSE resumability, and server-initiated JSON-RPC requests over Streamable HTTP. Authorization remains optional, but when used with HTTP transports it is based on OAuth 2.1, RFC 9728 Protected Resource Metadata, RFC 8707 resource indicators, PKCE, and OAuth authorization-server discovery.

Primary caveat: first-party configuration and session/auth guidance for Claude, OpenAI/Codex, Gemini, Cursor, GitHub Copilot, and Windsurf was not independently verified in the delegated pass; treat client compatibility as requiring live acceptance rather than as a verified finding.

## 1. Latest Specification And Official Repository

- The official specification page labels **Version 2026-07-28** as “latest” and defines MCP as an open protocol using JSON-RPC 2.0 between hosts, clients, and servers.  
  Source: <https://modelcontextprotocol.io/specification/2026-07-28>
- The 2026-07-28 changelog states that it lists changes since previous revision **2025-11-25**.  
  Source: <https://modelcontextprotocol.io/specification/2026-07-28/changelog>
- The official GitHub organization is <https://github.com/modelcontextprotocol>.
- The official specification/documentation repository is <https://github.com/modelcontextprotocol/modelcontextprotocol>. Its releases page is <https://github.com/modelcontextprotocol/modelcontextprotocol/releases>.

Date evidence is strongest from the versioned official specification page and changelog. The page itself states `2026-07-28` is latest; the changelog identifies the immediately preceding revision as `2025-11-25`.

## 2. Streamable HTTP Transport

The current Streamable HTTP transport is request-scoped and stateless.

Normative requirements from the current specification:

- The server **MUST** expose one MCP endpoint that supports HTTP POST.
- Every client JSON-RPC request or notification **MUST** be sent as a new HTTP POST.
- The client **MUST** include both `application/json` and `text/event-stream` in the `Accept` header.
- The POST body **MUST** contain one JSON-RPC request or notification; clients **MUST NOT** send JSON-RPC responses.
- A notification returns `202 Accepted` with no body when accepted.
- A request returns either a single JSON object or an SSE response stream scoped to that request.
- Every POST **MUST** include `MCP-Protocol-Version`; it **MUST** match the protocol version in the request body `_meta`. Mismatches **MUST** be rejected with `400 Bad Request` and `HeaderMismatch`.
- The server **MUST** validate the `Origin` header to prevent DNS rebinding.
- Local servers **SHOULD** bind only to localhost.
- Servers **SHOULD** implement authentication.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/transports>  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>

Session and lifecycle changes in 2026-07-28:

- Protocol-level sessions and the `Mcp-Session-Id` header are removed.
- The `initialize` / `notifications/initialized` handshake is removed.
- Every request carries protocol version, client capabilities, and optionally client identity in `_meta`.
- Servers **MUST** implement `server/discover`, which advertises supported protocol versions, capabilities, and identity.
- Clients **MAY** call `server/discover` first, but may also issue a normal RPC and handle `UnsupportedProtocolVersionError`.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/changelog>  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning>

SSE behavior:

- Request-scoped SSE streams may carry progress or log notifications before the final response.
- The final JSON-RPC response **SHOULD** terminate the stream.
- Servers **MUST NOT** send independent JSON-RPC requests on that stream.
- Closing the request’s SSE response stream is the cancellation signal.
- SSE resumability through `Last-Event-ID` is removed.
- Servers **SHOULD** set `X-Accel-Buffering: no` when initiating SSE so reverse proxies do not buffer events.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>  
<https://modelcontextprotocol.io/specification/2026-07-28/changelog>

Long-lived notifications:

- Clients that need server-initiated change notifications open `subscriptions/listen`.
- Its response is a long-lived SSE stream carrying only opted-in notification types.
- Request-scoped notifications such as progress remain on the original request’s response stream.

Source: <https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>

## 3. OAuth 2.0 / OAuth 2.1 Authorization

Authorization is optional. When used over HTTP-based transports, MCP authorization is based on **OAuth 2.1 IETF DRAFT `draft-ietf-oauth-v2-1-13`**, plus OAuth 2.0 bearer-token usage, authorization-server metadata, dynamic client registration, resource indicators, Protected Resource Metadata, RFC 9207 issuer identification, Client ID Metadata Documents, and OpenID Connect discovery/dynamic registration.

Source: <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization>

Role mapping:

- The MCP server is an OAuth 2.1 resource server.
- The MCP client is an OAuth 2.1 client.
- The authorization server issues tokens for the MCP server.
- STDIO implementations **SHOULD NOT** follow this HTTP authorization specification and should retrieve credentials from the environment instead.

Source: <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization>

Protected Resource Metadata and authorization-server discovery:

- MCP servers **MUST** implement OAuth 2.0 Protected Resource Metadata, RFC 9728.
- The Protected Resource Metadata document **MUST** include at least one authorization server in `authorization_servers`.
- MCP clients **MUST** use Protected Resource Metadata for authorization-server discovery.
- Servers can advertise the metadata URL through `WWW-Authenticate` on `401 Unauthorized`, or publish it at RFC 9728 well-known URIs.
- Clients **MUST** prefer the parsed `WWW-Authenticate` metadata URL when present, then fall back to the specified well-known URI order.
- Authorization servers **MUST** provide RFC 8414 metadata or OpenID Connect Discovery metadata.
- Clients **MUST** validate that the discovered metadata `issuer` exactly matches the issuer used to construct the discovery URL.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization>  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery>

Client registration:

- MCP supports Client ID Metadata Documents, pre-registration, and Dynamic Client Registration.
- Clients supporting all options **SHOULD** prefer, in order: pre-registration, Client ID Metadata Documents if supported, Dynamic Client Registration if supported, then asking the user to enter client information.
- Authorization servers and clients **SHOULD** support Client ID Metadata Documents.
- Dynamic Client Registration is now optional and retained for backward compatibility.
- When using Dynamic Client Registration, clients **MUST** specify an appropriate `application_type`; native-style clients **SHOULD** use `native`, browser-hosted web clients **SHOULD** use `web`.
- Persisted pre-registered or dynamically registered credentials **MUST** be keyed by authorization-server issuer.
- Clients **MUST NOT** reuse credentials across different authorization servers and **MUST** re-register when the authorization server changes.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration>  
<https://modelcontextprotocol.io/specification/2026-07-28/changelog>

Browser redirect and authorization-code protections:

- All authorization-server endpoints **MUST** use HTTPS.
- Redirect URIs **MUST** be either localhost or HTTPS.
- Clients **MUST** implement PKCE and verify authorization-server PKCE support before proceeding.
- Clients **MUST** use `S256` when technically capable.
- Authorization servers **MUST** validate exact redirect URIs.
- Clients **SHOULD** use and verify `state`.
- Authorization servers **SHOULD** include RFC 9207 `iss` in authorization responses.
- Clients **MUST** validate a present `iss` against the recorded issuer before redeeming the authorization code.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations>  
<https://modelcontextprotocol.io/specification/2026-07-28/changelog>

Token audience and resource validation:

- Clients **MUST** include the RFC 8707 `resource` parameter in authorization and token requests.
- MCP servers **MUST** validate that tokens were issued specifically for that MCP server.
- Servers **MUST** reject tokens that do not include the server in the audience claim unless the server otherwise verifies it is the intended recipient.
- MCP servers **MUST NOT** pass a client-issued MCP token through to an upstream API.

Source: <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations>

First-login/client connection behavior:

- The client issues an HTTP request to the MCP endpoint.
- On `401 Unauthorized`, it parses `WWW-Authenticate`, fetches Protected Resource Metadata, discovers authorization-server metadata, selects a registration mechanism, performs the authorization flow with PKCE, obtains tokens, and retries the protected MCP request.
- Scope selection **SHOULD** first use the `scope` parameter from the initial `WWW-Authenticate` challenge. If absent, clients **SHOULD** use `scopes_supported` from Protected Resource Metadata.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery>  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization>

## 4. Stateful Versus Stateless Deployment

Under the 2026-07-28 revision, MCP itself is stateless.

- The protocol has no connection-scoped session.
- Requests do not depend on a prior `initialize` handshake.
- Protocol version and client capabilities travel with each request.
- List endpoints no longer vary per connection.
- Servers needing cross-call state use explicit server-minted handles passed as ordinary tool arguments.
- SSE response streams are request-scoped.
- `subscriptions/listen` is the only long-lived notification stream described by the current transport.

Deployment implication:

- Ordinary JSON-RPC request handling can scale horizontally without session affinity.
- Reverse proxies and load balancers do not need to route all calls for one logical client to the same backend merely because of protocol state.
- Long-lived `subscriptions/listen` SSE connections still require normal long-lived HTTP connection handling; keeping that stream open is connection affinity at the HTTP-stream level, not protocol-session affinity.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/changelog>  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning>  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>

Backward compatibility:

- `2025-11-25` and earlier are legacy initialization-based protocol revisions.
- A dual-era server may support both legacy and modern behavior.
- A modern request is served statelessly under 2026-07-28 semantics.
- A legacy `initialize` request selects legacy session semantics.
- HTTP clients detect the server era by attempting a modern request and inspecting a `400 Bad Request` body before falling back.

Source: <https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning>

## 5. Mainstream Agent And Client Integration Guidance

**Not verified in this pass.** The delegated research reached its tool-call limit before opening first-party documentation for Claude/Claude Code/Claude Desktop, OpenAI/Codex, Gemini, Cursor, GitHub Copilot, and Windsurf.

Do not treat any client-specific transport, registration, OAuth, session, or affinity behavior in this section as verified. A follow-up pass should inspect each vendor’s current first-party MCP documentation and note the exact transport versions supported, because clients may still support legacy initialization-based MCP servers for compatibility.

## 6. Efficiency, Caching, Streaming, And High-Concurrency Guidance

Current official guidance favors independent request execution, explicit long-lived notification streams, caching hints, cancellation, and progress.

From the official transport and changelog:

- JSON-RPC batching is not the model in current Streamable HTTP: each JSON-RPC request or notification is its own HTTP POST.
- Requests return either compact JSON or request-scoped SSE.
- Progress and other request-scoped notifications flow on the request’s response stream.
- Long-lived list-change and resource-update notifications use one `subscriptions/listen` stream instead of per-resource HTTP subscriptions.
- Closing the SSE response stream is the cancellation signal; servers **SHOULD** stop work promptly.
- Servers **SHOULD** set `X-Accel-Buffering: no` for SSE to avoid proxy buffering.
- `Last-Event-ID` resumability is removed; clients **MUST** reissue a broken in-flight request with a new request ID.

Sources:  
<https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>  
<https://modelcontextprotocol.io/specification/2026-07-28/changelog>

Caching and list efficiency:

- 2026-07-28 adds required `ttlMs` and `cacheScope` fields on `tools/list`, `prompts/list`, `resources/list`, `resources/read`, and `resources/templates/list`.
- `ttlMs` is a freshness hint in milliseconds.
- `cacheScope` is either `public` or `private`, controlling whether shared intermediaries may cache the response.
- These fields complement existing list-changed notifications.
- Servers **SHOULD** return tools from `tools/list` in deterministic order to support client-side caching and improve prompt-cache hit rates.

Source: <https://modelcontextprotocol.io/specification/2026-07-28/changelog>

Long-running operations:

- Core experimental tasks moved to the official `io.modelcontextprotocol/tasks` extension.
- The redesigned extension replaces blocking `tasks/result` with polling via `tasks/get`, adds `tasks/update` for client-to-server input, removes `tasks/list`, and permits unsolicited task handles.
- Multi Round-Trip Requests use `InputRequiredResult` with `resultType: "input_required"`; clients retry the original request with `inputResponses`.

Source: <https://modelcontextprotocol.io/specification/2026-07-28/changelog>

Operational recommendations implied by these normative choices:

- Keep ordinary tool calls stateless and horizontally scalable.
- Persist only explicit application-level handles or task handles when the server intentionally supports durable work.
- Avoid protocol-level in-memory state shared across requests.
- Treat one long-lived `subscriptions/listen` SSE connection as a real long-lived HTTP connection; configure proxy timeouts and disable buffering.
- Cache list/read results according to `ttlMs` and `cacheScope`, and refresh on list-changed notifications when subscribed.
- Preserve request IDs carefully for cancellation, because there is no SSE resume in the current revision.
- Use `server/discover` when avoiding repeated unsupported-version probes is important.
- For high concurrency, do not bind ordinary requests to a backend instance; only long-lived streams need stable HTTP connection treatment.

## Conflicts, Gaps, And Verification Needs

1. **GitHub release-object confirmation is incomplete.** The official spec page and changelog establish `2026-07-28` and its predecessor, but the matching GitHub release page was not independently opened before the delegated pass reached its tool limit.
2. **First-party client matrix is missing.** Current client support may lag or include compatibility modes for legacy initialization-based MCP. This requires direct inspection of each vendor’s current documentation.
3. **Legacy deployments remain materially different.** Servers and clients supporting `2025-11-25` or earlier use initialization, sessions, server-initiated requests, and potentially SSE resume. Mixing eras requires the compatibility rules in the versioning page.
4. **OAuth 2.1 is still an IETF draft in MCP’s cited source.** MCP cites `draft-ietf-oauth-v2-1-13`; implementations should track whether the referenced draft or IETF status changes.
5. **“Stateless” applies to protocol state, not all application state.** Explicit handles, tasks, downstream database sessions, and user authorization are still server/application concerns.

## Primary Sources

1. MCP Specification, latest page: <https://modelcontextprotocol.io/specification/2026-07-28>
2. MCP 2026-07-28 changelog: <https://modelcontextprotocol.io/specification/2026-07-28/changelog>
3. MCP transports overview: <https://modelcontextprotocol.io/specification/2026-07-28/basic/transports>
4. Streamable HTTP transport: <https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>
5. Versioning and compatibility: <https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning>
6. MCP authorization overview: <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization>
7. Authorization-server discovery: <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery>
8. Client registration: <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration>
9. Authorization security considerations: <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations>
10. Official GitHub organization: <https://github.com/modelcontextprotocol>
11. Official specification repository: <https://github.com/modelcontextprotocol/modelcontextprotocol>
12. Repository releases: <https://github.com/modelcontextprotocol/modelcontextprotocol/releases>
