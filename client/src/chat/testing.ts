import { ApiError, UnauthorizedError, type Api, type HistoryQuery } from './api';
import type { SocketLike, StorageLike } from './ChatClient';
import type { ClientFrame, ConversationSummary, HistoryPage, Message, ServerFrame } from './protocol';

// Test doubles for ChatClient. Not imported by the app.

export class FakeSocket implements SocketLike {
  sent: ClientFrame[] = [];
  closedWith: { code?: number; reason?: string } | null = null;
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: ((ev: { code: number; reason: string }) => void) | null = null;

  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close(code?: number, reason?: string) {
    this.closedWith = { code, reason };
  }

  // server side
  open() {
    this.onopen?.();
  }
  receive(frame: ServerFrame) {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
  drop(code = 1006, reason = '') {
    this.onclose?.({ code, reason });
  }
  sentOfType<T extends ClientFrame['type']>(type: T) {
    return this.sent.filter((f): f is Extract<ClientFrame, { type: T }> => f.type === type);
  }
}

/**
 * An in-memory server with one conversation per pair and gapless seqs, like the real one. Paging
 * follows ConversationController: afterSeq ascending with hasMore, the latest page otherwise.
 */
export class FakeApi implements Api {
  user: string | null;
  down = false;
  messagesById = new Map<number, Message[]>();
  convs: { id: number; a: string; b: string }[] = [];
  /** Runs inside conversations() after the query "ran", before the response. */
  duringConversations: (() => void) | null = null;
  /** Runs inside messages() after the query "ran", before the response: a mid-fetch race. */
  duringMessages: ((q: HistoryQuery) => void) | null = null;
  calls: string[] = [];
  private nextId = 1;

  constructor(user: string | null) {
    this.user = user;
  }

  /** Stores a message as the server would, and returns it (to push as a live frame if wanted). */
  commit(from: string, to: string, text: string, clientMsgId = `cid-${this.nextId}`): Message {
    let conv = this.convs.find((c) => (c.a === from && c.b === to) || (c.a === to && c.b === from));
    if (!conv) {
      conv = { id: this.convs.length + 1, a: from, b: to };
      this.convs.push(conv);
      this.messagesById.set(conv.id, []);
    }
    const list = this.messagesById.get(conv.id)!;
    const m: Message = {
      id: this.nextId++, conversationId: conv.id, seq: list.length + 1, from, to, text, clientMsgId, ts: 1_000 * this.nextId,
    };
    list.push(m);
    return m;
  }

  private check() {
    if (this.down) return Promise.reject(new ApiError(0, "Can't reach the server"));
    if (!this.user) return Promise.reject(new UnauthorizedError());
    return null;
  }

  me() {
    this.calls.push('me');
    return this.check() ?? Promise.resolve(this.user!);
  }
  login(username: string) {
    this.user = username;
    return Promise.resolve(username);
  }
  register(username: string) {
    return this.login(username);
  }
  logout() {
    this.user = null;
    return Promise.resolve();
  }

  conversations(): Promise<ConversationSummary[]> {
    this.calls.push('conversations');
    const err = this.check();
    if (err) return err;
    const me = this.user!;
    const list = structuredClone(
      this.convs
        .filter((c) => c.a === me || c.b === me)
        .map((c) => {
          const msgs = this.messagesById.get(c.id)!;
          const last = msgs[msgs.length - 1];
          return {
            id: c.id, otherUser: c.a === me ? c.b : c.a, lastSeq: msgs.length,
            lastSender: last?.from ?? null, lastBody: last?.text ?? null, lastAt: last?.ts ?? null,
            otherDeliveredSeq: 0, otherReadSeq: 0, unreadCount: msgs.filter((m) => m.from !== me).length,
          };
        }),
    );
    this.duringConversations?.();
    return Promise.resolve(list);
  }

  messages(id: number, q: HistoryQuery): Promise<HistoryPage> {
    this.calls.push(`messages ${JSON.stringify(q)}`);
    const err = this.check();
    if (err) return err;
    const all = this.messagesById.get(id) ?? [];
    const limit = q.limit ?? 50;
    let page: HistoryPage;
    if (q.afterSeq !== undefined) {
      const after = all.filter((m) => m.seq > q.afterSeq!);
      page = { messages: after.slice(0, limit), nextBeforeSeq: null, hasMore: after.length > limit };
    } else {
      const before = all.filter((m) => m.seq < (q.beforeSeq ?? Infinity));
      const messages = before.slice(-limit);
      const hasMore = messages.length > 0 && messages[0].seq > 1;
      page = { messages, nextBeforeSeq: hasMore ? messages[0].seq : null, hasMore };
    }
    // Copy before the hook runs, so a message it commits isn't in this response: that's the race.
    const response = structuredClone(page);
    this.duringMessages?.(q);
    return Promise.resolve(response);
  }
}

export function memoryStorage(): StorageLike & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  };
}
