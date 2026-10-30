import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Vite resolves extensionless source imports; mirror that only while loading these TS modules.
const sourceImports = registerHooks({
  resolve(specifier: string, context: { parentURL?: string }, nextResolve: (s: string, c: unknown) => unknown) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const { parseRedirectUris, toClientWrite, validateClientForm } = await import('../src/views/admin/clientForm.ts');
sourceImports.deregister();

const base = {
  name: '客户端 A',
  type: 'CONFIDENTIAL',
  status: 'ACTIVE',
  authMethods: ['client_secret_basic'],
  redirectUris: ['https://client.example.test/callback'],
  allowedScopes: ['mcp:catalog'],
  jwksUri: '',
};

test('parseRedirectUris splits lines, trims and drops blanks', () => {
  assert.deepEqual(parseRedirectUris(' https://a.test/cb \n\n http://127.0.0.1:5175/cb\n'), ['https://a.test/cb', 'http://127.0.0.1:5175/cb']);
  assert.deepEqual(parseRedirectUris(''), []);
});

test('validateClientForm accepts contract-valid confidential and public clients', () => {
  assert.equal(validateClientForm(base), '');
  assert.equal(validateClientForm({ ...base, authMethods: ['private_key_jwt'], jwksUri: 'https://client.example.test/jwks.json' }), '');
  assert.equal(validateClientForm({ ...base, type: 'PUBLIC', authMethods: ['none'], redirectUris: ['http://localhost:5175/cb'] }), '');
  assert.equal(validateClientForm({ ...base, redirectUris: [] }), '');
});

test('validateClientForm mirrors server required fields and enum rules', () => {
  assert.match(validateClientForm({ ...base, name: '   ' }), /名称/);
  assert.match(validateClientForm({ ...base, name: 'x'.repeat(201) }), /名称/);
  assert.match(validateClientForm({ ...base, type: 'OTHER' }), /类型/);
  assert.match(validateClientForm({ ...base, status: 'UNKNOWN' }), /状态/);
  assert.match(validateClientForm({ ...base, authMethods: [] }), /认证方式/);
  assert.match(validateClientForm({ ...base, authMethods: ['totp'] }), /认证方式/);
  assert.match(validateClientForm({ ...base, allowedScopes: [] }), /Scope/);
  assert.match(validateClientForm({ ...base, allowedScopes: ['mcp:admin'] }), /Scope/);
});

test('validateClientForm enforces PUBLIC-only-none and CONFIDENTIAL-no-none', () => {
  assert.match(validateClientForm({ ...base, type: 'PUBLIC', authMethods: ['client_secret_basic'] }), /PUBLIC/);
  assert.match(validateClientForm({ ...base, type: 'PUBLIC', authMethods: ['none', 'client_secret_basic'] }), /PUBLIC/);
  assert.match(validateClientForm({ ...base, type: 'CONFIDENTIAL', authMethods: ['none'] }), /CONFIDENTIAL/);
});

test('validateClientForm enforces redirect URI rules and the 10-entry cap', () => {
  assert.match(validateClientForm({ ...base, redirectUris: ['http://client.example.test/cb'] }), /回调地址无效/);
  assert.match(validateClientForm({ ...base, redirectUris: ['https://*.example.test/cb'] }), /回调地址无效/);
  assert.match(validateClientForm({ ...base, redirectUris: ['https://client.example.test/cb#frag'] }), /回调地址无效/);
  assert.match(validateClientForm({ ...base, redirectUris: ['https://user:pw@client.example.test/cb'] }), /回调地址无效/);
  assert.match(validateClientForm({ ...base, redirectUris: ['https://client.example.test/a/../cb'] }), /回调地址无效/);
  const eleven = Array.from({ length: 11 }, (_, i) => `https://client.example.test/cb${i}`);
  assert.match(validateClientForm({ ...base, redirectUris: eleven }), /10/);
});

test('validateClientForm requires https JWKS and requires it for private_key_jwt-only', () => {
  assert.match(validateClientForm({ ...base, jwksUri: 'http://client.example.test/jwks.json' }), /https/);
  assert.match(validateClientForm({ ...base, jwksUri: 'not-a-url' }), /JWKS/);
  assert.match(validateClientForm({ ...base, authMethods: ['private_key_jwt'], jwksUri: '' }), /JWKS 地址必填/);
  // 含 client_secret_* 时 JWKS 可省略
  assert.equal(validateClientForm({ ...base, authMethods: ['private_key_jwt', 'client_secret_basic'], jwksUri: '' }), '');
});

test('toClientWrite emits every ClientWrite field and nulls an empty jwksUri', () => {
  const body = toClientWrite({ ...base, name: '  客户端 A  ' });
  assert.deepEqual(body, {
    name: '客户端 A',
    type: 'CONFIDENTIAL',
    authMethods: ['client_secret_basic'],
    redirectUris: ['https://client.example.test/callback'],
    allowedScopes: ['mcp:catalog'],
    jwksUri: null,
    status: 'ACTIVE',
  });
  assert.deepEqual(Object.keys(body).sort(), ['allowedScopes', 'authMethods', 'jwksUri', 'name', 'redirectUris', 'status', 'type']);
  assert.equal(toClientWrite({ ...base, jwksUri: ' https://client.example.test/jwks.json ' }).jwksUri, 'https://client.example.test/jwks.json');
});
