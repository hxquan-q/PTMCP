import { useEffect, useState } from 'react';
import { toOAuthPageError, type OAuthPageError } from './model';

export type AsyncState<T> = { kind: 'loading' } | { kind: 'ready'; value: T } | { kind: 'error'; error: OAuthPageError };

/** 加载函数必须是模块级稳定引用，否则每次渲染都会重新请求。 */
export function useAsyncContext<T>(load: (signal: AbortSignal) => Promise<T>): AsyncState<T> {
  const [state, setState] = useState<AsyncState<T>>({ kind: 'loading' });
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    load(controller.signal).then(
      (value) => {
        if (active) setState({ kind: 'ready', value });
      },
      (error: unknown) => {
        if (active && !controller.signal.aborted) setState({ kind: 'error', error: toOAuthPageError(error) });
      },
    );
    return () => {
      active = false;
      controller.abort();
    };
  }, [load]);
  return state;
}
