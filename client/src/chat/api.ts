import type { ConversationSummary, HistoryPage } from './protocol';

/** Any 401. The ChatClient treats it as "the session is over" wherever it happens. */
export class UnauthorizedError extends Error {
  constructor(message = 'Not logged in') {
    super(message);
  }
}

/** A non-2xx response; `message` is the server's user-facing text when it sent one. */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export interface HistoryQuery {
  beforeSeq?: number;
  afterSeq?: number;
  limit?: number;
}

/** The REST surface the ChatClient needs; tests substitute an in-memory fake. */
export interface Api {
  me(): Promise<string>;
  login(username: string, password: string): Promise<string>;
  register(username: string, password: string): Promise<string>;
  logout(): Promise<void>;
  conversations(): Promise<ConversationSummary[]>;
  messages(conversationId: number, query: HistoryQuery): Promise<HistoryPage>;
}

export function httpApi(base = ''): Api {
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      // same-origin (dev: the Vite proxy; later: nginx), so the HttpOnly cookie goes along
      // without credentials: 'include' and without CORS.
      res = await fetch(base + path, init);
    } catch {
      throw new ApiError(0, "Can't reach the server");
    }
    if (res.status === 401) {
      // Login answers a wrong password with 401 too; keep its message for the form.
      const body = await res.json().catch(() => ({}));
      throw new UnauthorizedError(body.error ?? 'Not logged in');
    }
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(res.status, body.error ?? `Request failed (${res.status})`);
    }
    return res.status === 204 ? (undefined as T) : res.json();
  }

  const post = <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: 'POST',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });

  return {
    me: () => request<{ username: string }>('/api/auth/me').then((b) => b.username),
    login: (username, password) =>
      post<{ username: string }>('/api/auth/login', { username, password }).then((b) => b.username),
    register: (username, password) =>
      post<{ username: string }>('/api/auth/register', { username, password }).then((b) => b.username),
    logout: () => post<void>('/api/auth/logout'),
    conversations: () => request<ConversationSummary[]>('/api/conversations'),
    messages: (id, q) => {
      const params = new URLSearchParams();
      if (q.beforeSeq !== undefined) params.set('beforeSeq', String(q.beforeSeq));
      if (q.afterSeq !== undefined) params.set('afterSeq', String(q.afterSeq));
      if (q.limit !== undefined) params.set('limit', String(q.limit));
      return request<HistoryPage>(`/api/conversations/${id}/messages?${params}`);
    },
  };
}
