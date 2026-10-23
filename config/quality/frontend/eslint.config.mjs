// Adapted from plus-ui, MIT (see UPSTREAM-LICENSE and quality-tooling.md).
// Preserve its flat JS/TS/Vue parser chain; PTMCP tightens semantic rules.
import globals from 'globals';
import pluginJs from '@eslint/js';
import tseslint from 'typescript-eslint';
import pluginVue from 'eslint-plugin-vue';
import configPrettier from 'eslint-config-prettier';
import { existsSync, readFileSync } from 'node:fs';

const autoImportFile = new URL('./.eslintrc-auto-import.json', import.meta.url);
// Before Vite generates this file, unknown globals remain subject to lint/typecheck.
const autoImportGlobals = existsSync(autoImportFile) ? JSON.parse(readFileSync(autoImportFile, 'utf8')).globals : {};

export default [
  { ignores: ['**/node_modules/**', '**/dist/**', '**/coverage/**', '**/auto-imports.d.ts', '**/components.d.ts'] },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  { languageOptions: { globals: { ...globals.browser, ...autoImportGlobals } } },
  { files: ['**/*.config.{js,mjs,cjs}'], languageOptions: { globals: globals.node } },
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
    rules: {
      'vue/multi-word-component-names': ['error', { ignores: ['App'] }],
      'vue/component-definition-name-casing': ['error', 'PascalCase'],
      'vue/component-name-in-template-casing': ['error', 'PascalCase', { registeredComponentsOnly: true }],
      'vue/prop-name-casing': ['error', 'camelCase'],
      'vue/attribute-hyphenation': ['error', 'always'],
      'vue/valid-define-props': 'error',
      'vue/require-prop-types': 'error',
      'vue/require-default-prop': 'error',
      'vue/require-valid-default-prop': 'error',
      'vue/no-mutating-props': 'error',
      'vue/require-explicit-emits': 'error',
      'vue/no-v-html': 'error'
    }
  },
  { rules: { '@typescript-eslint/no-explicit-any': 'error' } },
  // Last: disable competing layout rules. Prettier runs separately; CI never fixes files.
  configPrettier
];
