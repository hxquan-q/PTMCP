/** A page read intent owns one request and rejects any superseded/cleared response. */
export class PortalRequest {
  private controller: AbortController | null = null;
  private sequence = 0;
  private disposed = false;

  cancel(): void {
    this.sequence += 1;
    this.controller?.abort();
    this.controller = null;
  }

  dispose(): void {
    this.disposed = true;
    this.cancel();
  }

  async run<T>(work: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
    if (this.disposed) return undefined;
    this.cancel();
    const ownSequence = this.sequence;
    this.controller = new AbortController();
    try {
      const value = await work(this.controller.signal);
      return ownSequence === this.sequence ? value : undefined;
    } catch (error) {
      if (ownSequence !== this.sequence || (error instanceof DOMException && error.name === 'AbortError')) return undefined;
      throw error;
    }
  }
}
