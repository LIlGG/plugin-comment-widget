import { msg } from '@lit/localize';
import { css, html, LitElement } from 'lit';
import { property, state } from 'lit/decorators.js';
import baseStyles from './styles/base';

interface TurnstileApi {
  render(container: HTMLElement, options: Record<string, unknown>): string;
  reset(id: string): void;
  remove(id: string): void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

let loading: Promise<TurnstileApi> | undefined;
function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) {
    return Promise.resolve(window.turnstile);
  }
  if (loading) {
    return loading;
  }
  loading = new Promise<TurnstileApi>((resolve, reject) => {
    const script = document.createElement('script');
    script.src =
      'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
    script.async = true;
    const timeout = window.setTimeout(fail, 15000);
    function fail() {
      window.clearTimeout(timeout);
      script.remove();
      loading = undefined;
      reject(new Error('Unable to load Turnstile'));
    }
    script.onerror = fail;
    script.onload = () => {
      window.clearTimeout(timeout);
      if (!window.turnstile) {
        fail();
        return;
      }
      resolve(window.turnstile);
    };
    document.head.append(script);
  });
  return loading;
}

export class TurnstileCaptcha extends LitElement {
  @property() siteKey = '';
  @state() token = '';
  @state() failed = false;
  private widgetId?: string;
  private generation = 0;

  override connectedCallback() {
    super.connectedCallback();
    void this.updateComplete.then(() => this.mount());
  }

  override updated(changes: Map<string, unknown>) {
    if (changes.has('siteKey')) {
      void this.mount();
    }
  }

  private async mount() {
    const generation = ++this.generation;
    this.remove();
    this.failed = false;
    if (!this.siteKey || !this.isConnected) {
      this.failed = true;
      return;
    }
    try {
      const api = await loadTurnstile();
      if (!this.isConnected || generation !== this.generation) {
        return;
      }
      const container =
        this.renderRoot.querySelector<HTMLElement>('.challenge');
      if (!container) {
        return;
      }
      this.widgetId = api.render(container, {
        sitekey: this.siteKey,
        action: 'comment',
        size: 'flexible',
        'response-field': false,
        callback: (token: string) => {
          this.token = token;
          this.failed = false;
        },
        'expired-callback': () => {
          this.token = '';
        },
        'error-callback': () => {
          this.token = '';
          this.failed = true;
        },
        'timeout-callback': () => {
          this.token = '';
          this.failed = true;
        },
      });
    } catch {
      if (this.isConnected && generation === this.generation) {
        this.failed = true;
      }
    }
  }

  reset() {
    this.token = '';
    if (this.widgetId !== undefined) {
      window.turnstile?.reset(this.widgetId);
    }
  }

  private remove() {
    this.token = '';
    if (this.widgetId !== undefined) {
      window.turnstile?.remove(this.widgetId);
    }
    this.widgetId = undefined;
  }

  override disconnectedCallback() {
    this.generation++;
    this.remove();
    super.disconnectedCallback();
  }

  static override styles = [
    ...baseStyles,
    css`:host { display: block; } @unocss-placeholder;`,
  ];

  override render() {
    return html`<div class="challenge"></div>${this.failed ? html`<button class="mt-2 text-xs text-text-2 hover:text-text-1 underline" type="button" @click=${this.mount}>${msg('Verification unavailable. Click to retry.')}</button>` : ''}`;
  }
}
customElements.get('turnstile-captcha') ||
  customElements.define('turnstile-captcha', TurnstileCaptcha);
