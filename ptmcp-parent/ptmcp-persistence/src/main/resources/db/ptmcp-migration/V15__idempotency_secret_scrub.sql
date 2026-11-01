-- One-time scrub of replay-cache rows written before client-create responses were sanitized:
-- those persisted bodies could still contain a plaintext client secret. Idempotency leases are
-- a disposable replay cache, not audit evidence, and RUNNING/UNKNOWN rows never carry a body,
-- so only SUCCEEDED client-create entries are dropped.
DELETE FROM api_idempotency
 WHERE route_key LIKE 'clients.create:%'
   AND state = 'SUCCEEDED';
