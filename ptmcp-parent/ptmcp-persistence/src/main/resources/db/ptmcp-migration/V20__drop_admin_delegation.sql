-- S4 (DEV-20261104-permission-simplification): the delegation surface is gone.
--
-- Rationale: with a single platform administrator and no capability codes there is nobody to
-- delegate to and nothing left to delegate; the management gate (S2) admits the admin and denies
-- everyone else outright. admin_delegation (V3) only fed the deleted DelegationStore, the scope
-- gates and the admin-delegation console, all removed in this slice.
DROP TABLE admin_delegation;
