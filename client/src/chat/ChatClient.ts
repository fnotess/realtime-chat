import { ApiError, UnauthorizedError, type Api } from './api';
import {
  MAX_TEXT_CHARS,
  SESSION_ENDED,
  type ClientFrame,
  type ConversationSummary,
  type Message,
  type ServerFrame,
} from './protocol';
import { newUuid } from './uuid';

// The whole real-time protocol lives here, with no React and no DOM globals: socket, API, storage,
// randomness and clock are all injected. React subscribes to immutable snapshots and calls methods;
// the tests drive the same class with a fake socket and fake timers.

/** The subset of the browser WebSocket this class uses, so tests can hand in a fake. */
export interface SocketLike {
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: (() => void) | null;
  onmessage: ((ev: { data: string }) => void) | null;
  onclose: ((ev: { code: number; reason: string }) => void) | null;
}

export type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export interface ChatClientOptions {
  api: Api;
  wsUrl: string;
  createSocket?: (url: string) => SocketLike;
  storage?: StorageLike;
  random?: () => number;
  now?: () => number;
}

// ---- snapshot (what React renders) -------------------------------------------------------------

export type Tick = 'pending' | 'sent' | 'delivered' | 'read' | 'failed';

export type ConnectionState =
  | { state: 'idle' }
  | { state: 'connecting' }
  | { state: 'syncing' }
  | { state: 'online' }
  | { state: 'waiting'; retryAt: number };

export interface MessageView {
  /**
   * sender:clientMsgId. Stable from the moment a message is typed through its ack, so React keeps
   * the same bubble instead of swapping it. Unique because the server enforces
   * UNIQUE (sender, clientMsgId); the sender part stops another user reusing our id to collide.
   */
  key: string;
  mine: boolean;
  text: string;
  ts: number;
  tick: Tick | null;
  error?: string;
}

export interface ChatListItem {
  other: string;
  preview: { text: string; mine: boolean; tick: Tick | null } | null;
  ts: number | null;
  unread: number;
}

export interface OpenChatView {
  other: string;
  messages: MessageView[];
  loading: boolean;
  hasOlder: boolean;
  loadingOlder: boolean;
}

export interface ChatSnapshot {
  auth: 'checking' | 'loggedOut' | 'loggedIn';
  me: string | null;
  /** Why the user is on the login screen when it wasn't their choice (session ended…). */
  notice: string | null;
  connection: ConnectionState;
  browserOnline: boolean;
  outboxSize: number;
  chats: ChatListItem[];
  open: OpenChatView | null;
}

// ---- internal state ----------------------------------------------------------------------------

interface OutboxItem {
  to: string;
  text: string;
  clientMsgId: string;
  createdAt: number;
}

interface Chat {
  other: string;
  /** null for a chat started from "New chat" that has no message yet: the server has no row. */
  id: number | null;
  msgs: Map<number, Message>;
  seqs: Set<number>;
  /**
   * Highest seq held with no gaps below it (down to where loading started). null until the chat
   * is opened. It's the cursor for the reconnect catch-up, so it must never pass a missing seq.
   */
  upTo: number | null;
  /** Seqs of the other member's messages we hold. */
  theirSeqs: Set<number>;
  /**
   * The other member's newest message at or below upTo: what "read" can cover. Our own messages
   * never need reading, so they don't earn a receipt frame (and a push to the other side) each.
   */
  readableUpTo: number;
  lastSeq: number;
  last: { seq: number; text: string; from: string; ts: number } | null;
  otherDelivered: number;
  otherRead: number;
  unread: number;
  /** The seq the server's unreadCount already covered; only messages above it add to `unread`. */
  counted: number;
  wantDelivered: number;
  wantRead: number;
  sentDelivered: number;
  sentRead: number;
  oldestSeq: number | null;
  hasOlder: boolean;
  loading: boolean;
  loadingOlder: boolean;
  failed: { clientMsgId: string; text: string; ts: number; error: string }[];
}

const PAGE = 50;
const CATCH_UP_PAGE = 100;
const RECEIPT_DELAY_MS = 300;
const BACKOFF_BASE_MS = 1000;
const BACKOFF_CAP_MS = 30_000;
const SERVER_ERROR_RETRY_MS = 2000;

const SESSION_ENDED_NOTICE = 'Your session has ended. Please log in again.';

export class ChatClient {
  private readonly api: Api;
  private readonly wsUrl: string;
  private readonly createSocket: (url: string) => SocketLike;
  private readonly storage: StorageLike;
  private readonly random: () => number;
  private readonly now: () => number;

  private listeners = new Set<() => void>();
  private snapshot: ChatSnapshot | null = null;

  private started = false;
  private auth: ChatSnapshot['auth'] = 'checking';
  private me: string | null = null;
  private notice: string | null = null;
  // Bumped on every login/logout. Async work captures it and drops its result if it changed, so a
  // fetch that was in flight at logout can't write the old user's data into the next session.
  private epoch = 0;

  private ws: SocketLike | null = null;
  private ready = false;
  private syncing = false;
  private buffered: Message[] = [];
  private wantConnected = false;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private receiptTimer: ReturnType<typeof setTimeout> | null = null;
  private connection: ConnectionState = { state: 'idle' };
  private browserOnline = true;
  private visible = true;

  private chats = new Map<string, Chat>();
  private byId = new Map<number, Chat>();
  private openOther: string | null = null;
  private outbox = new Map<string, OutboxItem>();

  constructor(opts: ChatClientOptions) {
    this.api = opts.api;
    this.wsUrl = opts.wsUrl;
    this.createSocket = opts.createSocket ?? browserSocket;
    this.storage = opts.storage ?? memoryStorage();
    this.random = opts.random ?? Math.random;
    this.now = opts.now ?? Date.now;
  }

  // ---- React-facing API ------------------------------------------------------------------------

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): ChatSnapshot => (this.snapshot ??= this.buildSnapshot());

  /**
   * On page load: the HttpOnly cookie survives a refresh but the page can't read it, so ask the
   * server who we are, then reconnect and restore.
   */
  async start(): Promise<void> {
    // React's StrictMode runs mount effects twice in development; one page load is one start.
    if (this.started) return;
    this.started = true;
    const epoch = this.epoch;
    try {
      const user = await this.api.me();
      if (epoch === this.epoch) this.startSession(user);
    } catch (e) {
      if (epoch !== this.epoch) return;
      this.auth = 'loggedOut';
      if (!(e instanceof UnauthorizedError)) this.notice = errorText(e);
      this.changed();
    }
  }

  /** Throws with a user-facing message on failure; the form shows it. */
  async login(username: string, password: string): Promise<void> {
    this.startSession(await this.api.login(username, password));
  }

  async register(username: string, password: string): Promise<void> {
    this.startSession(await this.api.register(username, password));
  }

  async logout(): Promise<void> {
    this.wantConnected = false;
    // The server deletes the session and closes our sockets with 4001. The local session ends
    // whether or not that request succeeded: the user asked to leave.
    await this.api.logout().catch(() => undefined);
    this.endSession(null);
  }

  openChat(other: string | null): void {
    this.openOther = other;
    if (this.me) {
      if (other) this.storage.setItem(this.openKey(), other);
      else this.storage.removeItem(this.openKey());
    }
    const chat = other ? this.chats.get(other) : undefined;
    if (chat && chat.id !== null && chat.upTo === null) void this.loadLatest(chat);
    this.changed();
  }

  send(text: string): void {
    const to = this.openOther;
    const body = text.trim();
    if (!this.me || !to || !body || body.length > MAX_TEXT_CHARS) return;
    const item: OutboxItem = { to, text: body, clientMsgId: newUuid(), createdAt: this.now() };
    this.outbox.set(item.clientMsgId, item);
    this.saveOutbox();
    // Offline or mid-sync: it stays in the outbox and goes out, in order, once the socket is ready.
    if (this.canSend()) this.sendFrame({ type: 'send', to, clientMsgId: item.clientMsgId, text: body });
    this.changed();
  }

  async loadOlder(): Promise<void> {
    const chat = this.openOther ? this.chats.get(this.openOther) : undefined;
    if (!chat || chat.id === null || !chat.hasOlder || chat.loadingOlder || chat.oldestSeq === null) return;
    const epoch = this.epoch;
    chat.loadingOlder = true;
    this.changed();
    try {
      const page = await this.api.messages(chat.id, { beforeSeq: chat.oldestSeq, limit: PAGE });
      if (epoch !== this.epoch) return;
      page.messages.forEach((m) => this.applyMessage(m));
      if (page.messages.length) chat.oldestSeq = page.messages[0].seq;
      chat.hasOlder = page.hasMore;
    } catch (e) {
      if (this.handleAuthError(e, epoch)) return;
    } finally {
      chat.loadingOlder = false;
      if (epoch === this.epoch) this.changed();
    }
  }

  /** document.visibilityState, fed in by the UI: read receipts only go out for a visible tab. */
  setVisible(visible: boolean): void {
    this.visible = visible;
    this.changed();
  }

  /** navigator.onLine. Coming back online retries at once instead of sitting out the backoff. */
  setBrowserOnline(online: boolean): void {
    this.browserOnline = online;
    if (online) this.reconnectNow();
    this.changed();
  }

  reconnectNow(): void {
    if (this.wantConnected && !this.ws) this.connect();
  }

  // ---- session ---------------------------------------------------------------------------------

  private startSession(user: string): void {
    this.epoch++;
    this.me = user;
    this.auth = 'loggedIn';
    this.notice = null;
    this.chats.clear();
    this.byId.clear();
    this.loadOutbox();
    // sessionStorage is per tab, so each tab reopens its own chat after a refresh.
    this.openOther = this.storage.getItem(this.openKey());
    this.wantConnected = true;
    this.attempt = 0;
    this.connect();
    this.changed();
  }

  private endSession(notice: string | null): void {
    this.epoch++;
    this.wantConnected = false;
    this.clearTimers();
    this.dropSocket(1000, 'logout');
    // Cleared, not kept for next time: the next person to log in on this tab may be someone else.
    if (this.me) {
      this.storage.removeItem(this.outboxKey());
      this.storage.removeItem(this.openKey());
    }
    this.me = null;
    this.auth = 'loggedOut';
    this.notice = notice;
    this.connection = { state: 'idle' };
    this.chats.clear();
    this.byId.clear();
    this.outbox.clear();
    this.openOther = null;
    this.changed();
  }

  /** Any 401 means the session is gone, wherever it surfaced. Returns true if it handled `e`. */
  private handleAuthError(e: unknown, epoch: number): boolean {
    if (epoch !== this.epoch) return true;
    if (e instanceof UnauthorizedError) {
      this.endSession(SESSION_ENDED_NOTICE);
      return true;
    }
    return false;
  }

  // ---- connection ------------------------------------------------------------------------------

  private connect(): void {
    this.clearReconnectTimer();
    if (this.ws || !this.wantConnected) return;
    // No token in the URL: the session cookie is scoped by host, not port, so the browser sends it
    // on this upgrade too, and the server authenticates the socket from it.
    const socket = this.createSocket(this.wsUrl);
    this.ws = socket;
    this.connection = { state: 'connecting' };
    let opened = false;

    // Every handler checks it still belongs to the current socket: a replaced socket's late events
    // must not tear down its successor.
    socket.onopen = () => {
      opened = true;
    };
    socket.onmessage = (ev) => {
      if (this.ws !== socket) return;
      let frame: ServerFrame;
      try {
        frame = JSON.parse(ev.data);
      } catch {
        return;
      }
      this.onFrame(socket, frame);
    };
    socket.onclose = (ev) => {
      if (this.ws !== socket) return;
      this.ws = null;
      this.ready = false;
      this.syncing = false;
      this.buffered = [];
      if (ev.code === SESSION_ENDED) {
        this.endSession(SESSION_ENDED_NOTICE);
        return;
      }
      if (!opened) {
        void this.checkSessionThenRetry();
        return;
      }
      this.scheduleReconnect();
    };
    this.changed();
  }

  /**
   * A rejected upgrade (401, 403, 429) reaches the page only as a close with 1006: browsers hide
   * the HTTP status. So ask REST whether the session is still valid. If that fails too, the server
   * is down: keep backing off rather than giving up.
   */
  private async checkSessionThenRetry(): Promise<void> {
    const epoch = this.epoch;
    try {
      await this.api.me();
    } catch (e) {
      if (this.handleAuthError(e, epoch)) return;
    }
    if (epoch === this.epoch) this.scheduleReconnect();
  }

  /**
   * Exponential backoff with full jitter: a random delay in [0, min(30s, 1s × 2^attempt)).
   * The exponent stops a down server being hammered. The jitter matters more: a server restart
   * drops every client at the same instant, and fixed steps would bring them all back together at
   * 1s, 2s, 4s…, each step a spike of handshakes and catch-up fetches. Random delays spread the
   * same reconnects out into a trickle.
   */
  private scheduleReconnect(): void {
    if (!this.wantConnected) return;
    const delay = this.random() * Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** this.attempt);
    this.attempt++;
    this.connection = { state: 'waiting', retryAt: this.now() + delay };
    this.reconnectTimer = setTimeout(() => this.connect(), delay);
    this.changed();
  }

  private dropSocket(code: number, reason: string): void {
    const socket = this.ws;
    this.ws = null;
    this.ready = false;
    this.syncing = false;
    this.buffered = [];
    socket?.close(code, reason);
  }

  private canSend(): boolean {
    return this.ws !== null && this.ready && !this.syncing;
  }

  private sendFrame(frame: ClientFrame): void {
    // A socket that is closing silently drops this. Sends are safe anyway: they stay in the outbox
    // until acked, and receipts are re-sent after every reconnect.
    this.ws?.send(JSON.stringify(frame));
  }

  // ---- frames ----------------------------------------------------------------------------------

  private onFrame(socket: SocketLike, f: ServerFrame): void {
    switch (f.type) {
      case 'ready':
        void this.onReady(socket, f.user);
        break;
      case 'message': {
        const { type: _type, ...m } = f;
        if (this.syncing) this.buffered.push(m);
        else this.applyLive(m);
        break;
      }
      case 'ack': {
        const pending = this.outbox.get(f.clientMsgId);
        this.outbox.delete(f.clientMsgId);
        this.saveOutbox();
        if (pending && this.me) {
          this.applyLive({
            id: f.id, conversationId: f.conversationId, seq: f.seq, from: this.me,
            to: pending.to, text: pending.text, clientMsgId: f.clientMsgId, ts: f.ts,
          });
        } else {
          this.changed();
        }
        break;
      }
      case 'receipt':
        this.onReceipt(f);
        break;
      case 'error':
        this.onError(f);
        break;
    }
  }

  // ---- sync ------------------------------------------------------------------------------------
  //
  // Subscribe first, then fetch. "ready" arrives only after the server registered this socket
  // for fan-out, so anything committed from then on arrives live, and anything committed before
  // is in the fetch that starts now. Fetching first and then subscribing leaves a gap: a message
  // committed between the fetch and the registration is in neither. The overlap (a message both
  // fetched and received live) is expected; merging by id makes it harmless.
  //
  // Live messages that arrive during the fetch are buffered and applied after it. The server's
  // unreadCount is assigned per conversation during the sync, and a live message counted before
  // that assignment would be overwritten and lost from the badge.

  private async onReady(socket: SocketLike, user: string): Promise<void> {
    if (user !== this.me) {
      // The cookie is shared by all windows of a browser profile. Someone logged in as another
      // user in a different window, and this reconnect picked up their session. Showing their
      // chats under our name would be wrong; switch this tab to the account it's actually using.
      this.endSession(null);
      this.startSession(user);
      return;
    }
    const epoch = this.epoch;
    this.ready = true;
    this.syncing = true;
    this.buffered = [];
    this.connection = { state: 'syncing' };
    // A receipt sent just before the last disconnect may have been lost with the socket. Send the
    // current watermarks again once; the server keeps the max, so a duplicate costs nothing.
    for (const c of this.chats.values()) {
      c.sentDelivered = 0;
      c.sentRead = 0;
    }
    this.changed();

    try {
      await this.sync(socket, epoch);
    } catch (e) {
      if (this.handleAuthError(e, epoch)) return;
      // Half-synced isn't a state worth staying in: the catch-up cursor may be behind. Drop the
      // socket and let the backoff retry the whole thing.
      if (this.ws === socket) {
        this.dropSocket(1000, 'sync failed');
        this.scheduleReconnect();
      }
      return;
    }
    if (this.ws !== socket || epoch !== this.epoch) return;

    this.syncing = false;
    // Reset only after a full sync: a socket that opens and is closed at once (1008, a bouncing
    // server) must keep backing off instead of retrying immediately forever.
    this.attempt = 0;
    const buffered = this.buffered;
    this.buffered = [];
    buffered.forEach((m) => this.applyLive(m));
    this.resendOutbox();
    this.scheduleReceiptFlush();
    this.connection = { state: 'online' };
    this.changed();
  }

  private async sync(socket: SocketLike, epoch: number): Promise<void> {
    const stale = () => this.ws !== socket || epoch !== this.epoch;
    const list = await this.api.conversations();
    if (stale()) return;
    for (const s of list) {
      const c = this.ensureChat(s.otherUser);
      this.bindId(c, s.id);
      c.lastSeq = Math.max(c.lastSeq, s.lastSeq);
      c.otherDelivered = Math.max(c.otherDelivered, s.otherDeliveredSeq);
      c.otherRead = Math.max(c.otherRead, s.otherReadSeq);
      this.updateLast(c, summaryLast(s));
      // Only chats this tab has opened are caught up message by message. The rest need only their
      // summary until opened, which keeps a reconnect cheap with many conversations.
      if (c.upTo !== null && c.lastSeq > c.upTo) {
        let hasMore = true;
        while (hasMore) {
          const page = await this.api.messages(s.id, { afterSeq: c.upTo, limit: CATCH_UP_PAGE });
          if (stale()) return;
          page.messages.forEach((m) => this.applyMessage(m));
          // An empty page with hasMore would loop forever; it can't happen, but don't bet on it.
          hasMore = page.hasMore && page.messages.length > 0;
        }
      }
      // Assigned after the fetch, which counts as it applies. From here on only seqs above
      // `counted` add to the badge.
      c.unread = s.unreadCount;
      c.counted = s.lastSeq;
      // This device now knows everything up to lastSeq exists: that's "delivered".
      if (c.lastSeq > 0) this.queueReceipt(c, c.lastSeq, false);
    }
    // The chat that was open before a refresh (or the one open during the outage).
    const open = this.openOther ? this.chats.get(this.openOther) : undefined;
    if (open && open.id !== null && open.upTo === null) await this.loadLatest(open);
  }

  /** First open of a chat: its newest page. Older pages come from loadOlder (beforeSeq). */
  private async loadLatest(chat: Chat): Promise<void> {
    if (chat.loading || chat.id === null) return;
    const epoch = this.epoch;
    chat.loading = true;
    this.changed();
    try {
      const page = await this.api.messages(chat.id, { limit: PAGE });
      if (epoch !== this.epoch) return;
      if (chat.upTo === null) chat.upTo = page.messages.length ? page.messages[0].seq - 1 : 0;
      page.messages.forEach((m) => this.applyMessage(m));
      // Messages that arrived live before the chat was opened were skipped above as already known,
      // so they never advanced the cursor. Advance over them now.
      this.advanceUpTo(chat);
      chat.oldestSeq = page.messages.length ? page.messages[0].seq : null;
      chat.hasOlder = page.hasMore;
    } catch (e) {
      if (this.handleAuthError(e, epoch)) return;
      // Left unloaded (upTo stays null): the next sync retries it.
    } finally {
      chat.loading = false;
      if (epoch === this.epoch) this.changed();
    }
  }

  // ---- messages --------------------------------------------------------------------------------

  /** Idempotent by message id: the same message from history and from the socket is kept once. */
  private applyMessage(m: Message): Chat {
    const mine = m.from === this.me;
    const c = this.ensureChat(mine ? m.to : m.from);
    this.bindId(c, m.conversationId);
    if (c.msgs.has(m.id)) return c;
    c.msgs.set(m.id, m);
    c.seqs.add(m.seq);
    if (!mine) c.theirSeqs.add(m.seq);
    c.lastSeq = Math.max(c.lastSeq, m.seq);
    this.advanceUpTo(c);
    this.updateLast(c, { seq: m.seq, text: m.text, from: m.from, ts: m.ts });
    if (mine) {
      // Our own message came back through history before its ack did (the ack was lost with the
      // old socket). It's committed, so it's no longer pending, and resending it would only earn
      // a re-ack.
      if (this.outbox.delete(m.clientMsgId)) this.saveOutbox();
    } else {
      if (!this.isOpenAndVisible(c) && m.seq > c.counted) c.unread++;
      this.queueReceipt(c, m.seq, false);
    }
    return c;
  }

  private applyLive(m: Message): void {
    const c = this.applyMessage(m);
    // The first message of the chat that's open right now (a brand-new chat, from either side):
    // it has no cursor yet, so load it like an explicit open. Otherwise read receipts and the
    // reconnect catch-up would skip it.
    if (c.upTo === null && c.other === this.openOther) void this.loadLatest(c);
    this.changed();
  }

  /** Only over contiguous seqs: two senders' fan-out can reach a socket out of seq order. */
  private advanceUpTo(c: Chat): void {
    if (c.upTo === null) return;
    while (c.seqs.has(c.upTo + 1)) {
      c.upTo++;
      if (c.theirSeqs.has(c.upTo)) c.readableUpTo = c.upTo;
    }
  }

  private onReceipt(f: Extract<ServerFrame, { type: 'receipt' }>): void {
    const c = this.byId.get(f.conversationId);
    if (!c) return;
    if (f.user === this.me) {
      // We read it on another tab or device: clear the badge here too.
      if (f.readSeq >= c.lastSeq) c.unread = 0;
    } else {
      // Pushes carry absolute watermarks; max() means a late or duplicate one can't move ticks back.
      c.otherDelivered = Math.max(c.otherDelivered, f.deliveredSeq);
      c.otherRead = Math.max(c.otherRead, f.readSeq);
    }
    this.changed();
  }

  private onError(f: Extract<ServerFrame, { type: 'error' }>): void {
    // Errors without a clientMsgId are about receipts, which are safe to drop: the next receipt
    // carries a higher watermark.
    if (!f.clientMsgId) return;
    const item = this.outbox.get(f.clientMsgId);
    if (!item) return;
    if (f.code === 'server_error') {
      // Nothing was stored. Retry later with the same clientMsgId: even if it had been stored, the
      // unique (sender, clientMsgId) makes the retry a re-ack, not a duplicate.
      const id = item.clientMsgId;
      setTimeout(() => {
        const again = this.outbox.get(id);
        if (again && this.canSend()) this.sendFrame({ type: 'send', to: again.to, clientMsgId: id, text: again.text });
      }, SERVER_ERROR_RETRY_MS * (1 + this.random()));
      return;
    }
    // Anything else (unknown recipient, too long…) will never succeed: stop retrying and show why.
    this.outbox.delete(item.clientMsgId);
    this.saveOutbox();
    this.ensureChat(item.to).failed.push({
      clientMsgId: item.clientMsgId, text: item.text, ts: item.createdAt, error: describeSendError(f.code, item.to),
    });
    this.changed();
  }

  // ---- outbox ----------------------------------------------------------------------------------
  //
  // Every send stays here until its ack. After a reconnect, whatever is left is resent with the
  // SAME clientMsgId: if the first attempt was stored and only the ack was lost, the server's
  // UNIQUE (sender, clientMsgId) turns the resend into a re-ack of the original, not a duplicate.
  // sessionStorage rather than localStorage: it survives a refresh, and it's per tab. Two tabs
  // sharing one outbox would both resend the same items.

  private outboxKey = () => `chat.outbox.${this.me}`;
  private openKey = () => `chat.open.${this.me}`;

  private loadOutbox(): void {
    this.outbox.clear();
    try {
      const items: OutboxItem[] = JSON.parse(this.storage.getItem(this.outboxKey()) ?? '[]');
      for (const m of items) this.outbox.set(m.clientMsgId, m);
    } catch {
      // Corrupt entry: start empty rather than failing the whole login.
    }
  }

  private saveOutbox(): void {
    if (this.me) this.storage.setItem(this.outboxKey(), JSON.stringify([...this.outbox.values()]));
  }

  private resendOutbox(): void {
    for (const m of this.outbox.values()) {
      this.sendFrame({ type: 'send', to: m.to, clientMsgId: m.clientMsgId, text: m.text });
    }
  }

  // ---- receipts --------------------------------------------------------------------------------
  //
  // Receipts are watermarks, so only the highest seq per conversation matters. Collect for a short
  // while and send at most one frame of each kind per conversation: a catch-up of 200 messages is
  // one "delivered", not 200. A "read" also raises delivered on the server, so delivered is only
  // sent when it's ahead of the read (messages received but not read: hidden tab, or a gap).
  // Sending only one of the two would lose that delivered with nothing scheduled to resend it.

  private queueReceipt(c: Chat, seq: number, read: boolean): void {
    c.wantDelivered = Math.max(c.wantDelivered, seq);
    if (read) c.wantRead = Math.max(c.wantRead, seq);
    this.scheduleReceiptFlush();
  }

  private scheduleReceiptFlush(): void {
    this.receiptTimer ??= setTimeout(() => this.flushReceipts(), RECEIPT_DELAY_MS);
  }

  private flushReceipts(): void {
    this.receiptTimer = null;
    if (!this.ws || !this.ready) return; // kept in want*; onReady re-arms them for the next socket
    for (const c of this.chats.values()) {
      if (c.id === null) continue;
      if (c.wantRead > c.sentRead) {
        this.sendFrame({ type: 'read', conversationId: c.id, seq: c.wantRead });
        c.sentRead = c.wantRead;
        c.sentDelivered = Math.max(c.sentDelivered, c.wantRead);
      }
      if (c.wantDelivered > c.sentDelivered) {
        this.sendFrame({ type: 'delivered', conversationId: c.id, seq: c.wantDelivered });
        c.sentDelivered = c.wantDelivered;
      }
    }
  }

  private isOpenAndVisible(c: Chat): boolean {
    return c.other === this.openOther && this.visible;
  }

  /** Read = the open chat in a visible tab, up to their last message held without gaps. */
  private markOpenChatRead(): void {
    const c = this.openOther ? this.chats.get(this.openOther) : undefined;
    if (c && c.upTo !== null && this.isOpenAndVisible(c)) {
      c.unread = 0;
      if (c.readableUpTo > c.wantRead) this.queueReceipt(c, c.readableUpTo, true);
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private ensureChat(other: string): Chat {
    let c = this.chats.get(other);
    if (!c) {
      c = {
        other, id: null, msgs: new Map(), seqs: new Set(), upTo: null, theirSeqs: new Set(), readableUpTo: 0,
        lastSeq: 0, last: null,
        otherDelivered: 0, otherRead: 0, unread: 0, counted: 0,
        wantDelivered: 0, wantRead: 0, sentDelivered: 0, sentRead: 0,
        oldestSeq: null, hasOlder: false, loading: false, loadingOlder: false, failed: [],
      };
      this.chats.set(other, c);
    }
    return c;
  }

  private bindId(c: Chat, id: number): void {
    if (c.id === null) {
      c.id = id;
      this.byId.set(id, c);
    }
  }

  private updateLast(c: Chat, last: Chat['last']): void {
    if (last && (!c.last || last.seq >= c.last.seq)) c.last = last;
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  private clearTimers(): void {
    this.clearReconnectTimer();
    if (this.receiptTimer) clearTimeout(this.receiptTimer);
    this.receiptTimer = null;
  }

  private changed(): void {
    this.markOpenChatRead();
    this.snapshot = null;
    this.listeners.forEach((l) => l());
  }

  private tickFor(c: Chat, seq: number): Tick {
    if (seq <= c.otherRead) return 'read';
    if (seq <= c.otherDelivered) return 'delivered';
    return 'sent';
  }

  private buildSnapshot(): ChatSnapshot {
    const pendingByChat = new Map<string, OutboxItem[]>();
    for (const m of this.outbox.values()) {
      const list = pendingByChat.get(m.to) ?? [];
      list.push(m);
      pendingByChat.set(m.to, list);
    }

    const names = new Set([...this.chats.keys(), ...pendingByChat.keys()]);
    const chats: ChatListItem[] = [];
    for (const other of names) {
      const c = this.chats.get(other);
      const pending = pendingByChat.get(other);
      // A chat with no server row and nothing queued is a "New chat" that only exists while open.
      if ((!c || c.id === null) && !pending && other !== this.openOther) continue;
      const lastPending = pending?.[pending.length - 1];
      const item: ChatListItem = { other, preview: null, ts: null, unread: c?.unread ?? 0 };
      if (lastPending) {
        item.preview = { text: lastPending.text, mine: true, tick: 'pending' };
        item.ts = lastPending.createdAt;
      } else if (c?.last) {
        const mine = c.last.from === this.me;
        item.preview = { text: c.last.text, mine, tick: mine ? this.tickFor(c, c.last.seq) : null };
        item.ts = c.last.ts;
      }
      chats.push(item);
    }
    // Newest first; a chat with no messages yet (just started) goes on top, where it was typed.
    chats.sort((a, b) => (b.ts ?? Infinity) - (a.ts ?? Infinity) || a.other.localeCompare(b.other));

    let open: OpenChatView | null = null;
    if (this.openOther) {
      const c = this.chats.get(this.openOther);
      const messages: MessageView[] = c
        ? [...c.msgs.values()]
            .sort((a, b) => a.seq - b.seq)
            .map((m) => {
              const mine = m.from === this.me;
              return { key: `${m.from}:${m.clientMsgId}`, mine, text: m.text, ts: m.ts, tick: mine ? this.tickFor(c, m.seq) : null };
            })
        : [];
      const tail: MessageView[] = [
        ...(c?.failed ?? []).map((f) => ({
          key: `${this.me}:${f.clientMsgId}`, mine: true, text: f.text, ts: f.ts, tick: 'failed' as const, error: f.error,
        })),
        ...(pendingByChat.get(this.openOther) ?? []).map((m) => ({
          key: `${this.me}:${m.clientMsgId}`, mine: true, text: m.text, ts: m.createdAt, tick: 'pending' as const,
        })),
      ].sort((a, b) => a.ts - b.ts);
      open = {
        other: this.openOther,
        messages: [...messages, ...tail],
        loading: c?.loading ?? false,
        hasOlder: c?.hasOlder ?? false,
        loadingOlder: c?.loadingOlder ?? false,
      };
    }

    return {
      auth: this.auth,
      me: this.me,
      notice: this.notice,
      connection: this.connection,
      browserOnline: this.browserOnline,
      outboxSize: this.outbox.size,
      chats,
      open,
    };
  }
}

function summaryLast(s: ConversationSummary): Chat['last'] {
  if (s.lastSeq === 0 || s.lastBody === null || s.lastSender === null) return null;
  const ts = typeof s.lastAt === 'number' ? s.lastAt : s.lastAt ? Date.parse(s.lastAt) : 0;
  return { seq: s.lastSeq, text: s.lastBody, from: s.lastSender, ts };
}

function describeSendError(code: string, to: string): string {
  switch (code) {
    case 'unknown_recipient':
    case 'invalid_recipient':
      return `There's no user called "${to}".`;
    case 'self_send':
      return "You can't message yourself.";
    case 'text_too_long':
      return `Too long: the limit is ${MAX_TEXT_CHARS} characters.`;
    default:
      return 'Not sent.';
  }
}

function errorText(e: unknown): string {
  return e instanceof ApiError || e instanceof UnauthorizedError ? e.message : 'Something went wrong';
}

/** Adapts the browser WebSocket to SocketLike, so the class above never touches the global. */
function browserSocket(url: string): SocketLike {
  const ws = new WebSocket(url);
  const s: SocketLike = {
    send: (d) => ws.send(d),
    close: (code, reason) => ws.close(code, reason),
    onopen: null,
    onmessage: null,
    onclose: null,
  };
  ws.onopen = () => s.onopen?.();
  ws.onmessage = (e) => s.onmessage?.({ data: String(e.data) });
  ws.onclose = (e) => s.onclose?.({ code: e.code, reason: e.reason });
  return s;
}

function memoryStorage(): StorageLike {
  const m = new Map<string, string>();
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, v),
    removeItem: (k) => void m.delete(k),
  };
}
