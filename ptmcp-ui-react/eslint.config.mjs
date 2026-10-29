// 与 ptmcp-ui/eslint.config.mjs 同源的规则链（去 Vue 插件），语义规则保持一致。
import globals from 'globals';
import pluginJs from '@eslint/js';
import tseslint from 'typescript-eslint';
import configPrettier from 'eslint-config-prettier';

export default [
  { ignores: ['**/node_modules/**', '**/dist/**', '**/coverage/**'] },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
  { languageOptions: { globals: { ...globals.browser } } },
  { files: ['**/*.config.{js,mjs,cjs,ts}'], languageOptions: { globals: globals.node } },
  { rules: { '@typescript-eslint/no-explicit-any': 'error' } },
  // Last: disable competing layout rules. Prettier runs separately.
  configPrettier,
];
