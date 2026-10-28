import type { ResourceSelection } from './useAccessWorkspace';

export function findResourceSelection(nodes: ResourceSelection[], key: string): ResourceSelection | null {
  for (const node of nodes) {
    if (node.key === key) return node;
    const child = node.children?.length ? findResourceSelection(node.children, key) : null;
    if (child) return child;
  }
  return null;
}
