import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatClient } from './ChatClient';
import { FakeApi, FakeSocket, memoryStorage } from './testing';

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

/** Lets pending promise chains (fake API responses, async sync steps) run to completion. */
async function settle() {
  for (let i = 0; i < 20; i++) await Promise.resolve();
}

function setup(opts: { user?: string | null; api?: FakeApi; storage?: ReturnType<typeof memoryStorage>; random?: () => number } = {}) {
  const api = opts.api ?? new FakeApi(opts.user === undefined ? 'bob' : opts.user);
  const storage = opts.storage ?? memoryStorage();
  const sockets: FakeSocket[] = [];
  const client = new ChatClient({
    api,
    wsUrl: 'ws://test/',
    storage,
    random: opts.random ?? (() => 0.5),
    now: () => Date.now(),
    createSocket: () => {
      const s = new FakeSocket();
      sockets.push(s);
      return s;
    },
  });
  const last = () => sockets[sockets.length - 1];
  /** Server side of a successful connect: the upgrade completes, then the "ready" frame. */
  async function accept(): Promise<FakeSocket> {
    const s = last();
    s.open();
    s.receive({ type: 'ready', user: api.user! });
    await settle();
    return s;
  }
  const texts = () => client.getSnapshot().open?.messages.map((m) => m.text);
  return { client, api, storage, sockets, last, accept, texts };
}

describe('sync on reconnect', () => {
  it('merges fetched and live messages without duplicates, including live ones during the fetch', async () => {
    const storage = memoryStorage();
    storage.setItem('chat.open.bob', 'alice'); // bob had this chat open before the refresh
    const { client, api, last, accept, texts } = setup({ storage });
    api.commit('alice', 'bob', 'm1');
    api.commit('alice', 'bob', 'm2');

    await client.start();
    const first = await accept();
    expect(texts()).toEqual(['m1', 'm2']);

    first.drop(1006);
    api.commit('alice', 'bob', 'm3'); // sent while bob is offline
    await vi.advanceTimersByTimeAsync(500); // random 0.5 × 1s
    const second = last();
    expect(second).not.toBe(first);

    second.open();
    second.receive({ type: 'ready', user: 'bob' });
    // The conversation list query has run. m4 is committed now, after this socket was registered
    // but before the history fetch: it will arrive live AND in the fetch.
    const m4 = api.commit('alice', 'bob', 'm4');
    api.duringMessages = () => {
      api.duringMessages = null;
      second.receive({ type: 'message', ...m4 });
      // m5 is committed after the fetch's query ran: only the live frame has it.
      second.receive({ type: 'message', ...api.commit('alice', 'bob', 'm5') });
    };
    await settle();

    expect(texts()).toEqual(['m1', 'm2', 'm3', 'm4', 'm5']);
    expect(api.calls).toContain('messages {"afterSeq":2,"limit":100}');

    // The catch-up cursor covered all five with no gap, so the read receipt says 5.
    await vi.advanceTimersByTimeAsync(300);
    expect(second.sentOfType('read')).toEqual([{ type: 'read', conversationId: 1, seq: 5 }]);
  });

  it('fetches a long offline gap in pages until hasMore is false', async () => {
    const storage = memoryStorage();
    storage.setItem('chat.open.bob', 'alice');
    const { client, api, accept, texts } = setup({ storage });
    api.commit('alice', 'bob', 'hi');
    await client.start();
    const first = await accept();
    first.drop(1006);

    for (let i = 0; i < 150; i++) api.commit('alice', 'bob', `x${i}`);
    await vi.advanceTimersByTimeAsync(500);
    await accept();

    expect(api.calls.filter((c) => c.includes('afterSeq'))).toEqual([
      'messages {"afterSeq":1,"limit":100}',
      'messages {"afterSeq":101,"limit":100}',
    ]);
    expect(texts()).toHaveLength(151);
    expect(new Set(texts()).size).toBe(151);
  });

  it('never moves the catch-up cursor past a missing seq', async () => {
    const storage = memoryStorage();
    storage.setItem('chat.open.bob', 'alice');
    const { client, api, accept, texts, last } = setup({ storage });
    api.commit('alice', 'bob', 'm1');
    await client.start();
    const first = await accept();

    // Two senders' fan-out can reach a socket out of seq order: seq 3 is stored but its frame
    // hasn't arrived when seq 3's successor does, and then the socket drops.
    api.commit('alice', 'bob', 'm2');
    first.receive({ type: 'message', ...api.commit('bob', 'alice', 'm3 from my phone') });
    first.drop(1006);

    await vi.advanceTimersByTimeAsync(500);
    await accept();
    expect(api.calls).toContain('messages {"afterSeq":1,"limit":100}');
    expect(texts()).toEqual(['m1', 'm2', 'm3 from my phone']);
    await vi.advanceTimersByTimeAsync(300);
    // m2 is alice's newest message; m3 is our own, which needs no read receipt.
    expect(last().sentOfType('read')).toEqual([{ type: 'read', conversationId: 1, seq: 2 }]);
  });

  it('keeps the unread count of a live message that arrives during the sync', async () => {
    const { client, api, last } = setup();
    api.commit('carol', 'bob', 'while offline');
    await client.start();
    const s = last();
    s.open();
    // After the list query ran (it counts 1 unread), a second message arrives live. The sync then
    // assigns the server's count; applying the live one before that would lose it from the badge.
    api.duringConversations = () => {
      api.duringConversations = null;
      s.receive({ type: 'message', ...api.commit('carol', 'bob', 'during sync') });
    };
    s.receive({ type: 'ready', user: 'bob' });
    await settle();
    expect(client.getSnapshot().chats).toMatchObject([{ other: 'carol', unread: 2, preview: { text: 'during sync' } }]);
  });

  it("doesn't send a frame on a socket before its ready arrives", async () => {
    const { client, last } = setup();
    await client.start();
    client.openChat('alice');
    client.send('early');
    last().open(); // upgrade done, but not yet registered for fan-out
    await vi.advanceTimersByTimeAsync(1000);
    expect(last().sent).toEqual([]);
  });
});

describe('outbox', () => {
  it('resends unacked messages after a reconnect, in order, with the same clientMsgId', async () => {
    const { client, accept, storage } = setup({ user: 'alice' });
    await client.start();
    const first = await accept();
    client.openChat('bob');

    client.send('sent but never acked');
    const [original] = first.sentOfType('send');
    first.drop(1006);
    client.send('typed while offline');
    expect(client.getSnapshot().open?.messages.map((m) => m.tick)).toEqual(['pending', 'pending']);

    await vi.advanceTimersByTimeAsync(500);
    const second = await accept();
    const resent = second.sentOfType('send');
    expect(resent.map((f) => f.text)).toEqual(['sent but never acked', 'typed while offline']);
    expect(resent[0].clientMsgId).toBe(original.clientMsgId);

    resent.forEach((f, i) =>
      second.receive({ type: 'ack', clientMsgId: f.clientMsgId, id: i + 1, conversationId: 1, seq: i + 1, ts: 1 }),
    );
    expect(client.getSnapshot().open?.messages.map((m) => m.tick)).toEqual(['sent', 'sent']);
    expect(storage.getItem('chat.outbox.alice')).toBe('[]');
  });

  it('survives a page refresh: a new client on the same sessionStorage resends', async () => {
    const storage = memoryStorage();
    const before = setup({ user: 'alice', storage });
    await before.client.start();
    before.client.openChat('bob');
    before.client.send('before refresh'); // not ready yet: only in the outbox

    const after = setup({ user: 'alice', storage });
    await after.client.start();
    expect(after.client.getSnapshot().open?.other).toBe('bob');
    const socket = await after.accept();
    expect(socket.sentOfType('send').map((f) => f.text)).toEqual(['before refresh']);
  });

  it("doesn't resend a message whose ack was lost when history shows it was stored", async () => {
    const { client, api, last, accept } = setup({ user: 'alice' });
    await client.start();
    const first = await accept();
    client.openChat('bob');
    client.send('stored, ack lost');
    const [frame] = first.sentOfType('send');
    api.commit('alice', 'bob', frame.text, frame.clientMsgId); // the server stored it...
    first.drop(1006); // ...and the socket died before the ack arrived

    await vi.advanceTimersByTimeAsync(500);
    const second = await accept();
    expect(second.sentOfType('send')).toEqual([]);
    expect(client.getSnapshot().open?.messages.map((m) => [m.text, m.tick])).toEqual([['stored, ack lost', 'sent']]);
    expect(last()).toBe(second);
  });
});

describe('reconnect backoff', () => {
  /** Drops the current socket as a failed handshake would (never opened, server unreachable). */
  async function failHandshake(ctx: ReturnType<typeof setup>): Promise<number> {
    ctx.last().drop(1006);
    await settle();
    const c = ctx.client.getSnapshot().connection;
    if (c.state !== 'waiting') throw new Error(`expected waiting, got ${c.state}`);
    return c.retryAt - Date.now();
  }

  it('doubles the ceiling each attempt and caps it at 30s', async () => {
    const ctx = setup({ random: () => 1 }); // the top of the jitter range: the ceiling itself
    await ctx.client.start();
    ctx.api.down = true;

    const delays: number[] = [];
    for (let i = 0; i < 8; i++) {
      const delay = await failHandshake(ctx);
      delays.push(delay);
      const sockets = ctx.sockets.length;
      await vi.advanceTimersByTimeAsync(delay - 1);
      expect(ctx.sockets.length).toBe(sockets); // not a moment early
      await vi.advanceTimersByTimeAsync(1);
      expect(ctx.sockets.length).toBe(sockets + 1);
    }
    expect(delays).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000]);
  });

  it('picks a uniformly random delay below the ceiling (full jitter)', async () => {
    const draws = [0.1, 0.9, 0.5, 0];
    const ctx = setup({ random: () => draws.shift()! });
    await ctx.client.start();
    ctx.api.down = true;

    const delays: number[] = [];
    for (let i = 0; i < 4; i++) {
      delays.push(await failHandshake(ctx));
      await vi.advanceTimersByTimeAsync(delays[i]);
    }
    // ceilings 1s, 2s, 4s, 8s scaled by the draws: not a fixed schedule, and 0 is allowed
    expect(delays).toEqual([100, 1800, 2000, 0]);
  });

  it('starts over after a successful sync, not merely an open socket', async () => {
    const ctx = setup({ random: () => 1 });
    await ctx.client.start();
    const s = await ctx.accept();
    s.drop(1006);
    await settle();
    await vi.advanceTimersByTimeAsync(1000);

    // Opens, then is closed before ready (a bouncing server): still backing off.
    ctx.last().open();
    ctx.last().drop(1008);
    expect(ctx.client.getSnapshot().connection).toEqual({ state: 'waiting', retryAt: Date.now() + 2000 });

    await vi.advanceTimersByTimeAsync(2000);
    await ctx.accept();
    ctx.last().drop(1006);
    expect(ctx.client.getSnapshot().connection).toEqual({ state: 'waiting', retryAt: Date.now() + 1000 });
  });
});

describe('session end', () => {
  it('goes to the login screen on close code 4001 and never reconnects', async () => {
    const { client, accept, sockets, storage } = setup({ user: 'alice' });
    await client.start();
    const s = await accept();
    client.openChat('bob');
    client.send('unsent');

    s.drop(4001, 'Session ended');
    const snap = client.getSnapshot();
    expect(snap.auth).toBe('loggedOut');
    expect(snap.notice).toMatch(/session has ended/);
    expect(snap.chats).toEqual([]);
    expect(storage.getItem('chat.outbox.alice')).toBeNull();

    await vi.advanceTimersByTimeAsync(120_000);
    expect(sockets).toHaveLength(1);
  });

  it('treats a rejected handshake as logged out when REST says 401', async () => {
    const { client, api, sockets } = setup();
    await client.start();
    api.user = null; // session expired server-side; the upgrade is refused with 401
    sockets[0].drop(1006);
    await settle();
    expect(client.getSnapshot().auth).toBe('loggedOut');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(sockets).toHaveLength(1);
  });

  it('treats a 401 during sync as logged out', async () => {
    const { client, api, last } = setup();
    await client.start();
    api.user = null;
    last().open();
    last().receive({ type: 'ready', user: 'bob' });
    await settle();
    expect(client.getSnapshot().auth).toBe('loggedOut');
  });
});

describe('receipts', () => {
  it('sends delivered too when it is ahead of the read watermark in the same flush', async () => {
    const storage = memoryStorage();
    storage.setItem('chat.open.bob', 'alice');
    const { client, api, accept } = setup({ storage });
    api.commit('alice', 'bob', 'm1');
    await client.start();
    const s = await accept();
    await vi.advanceTimersByTimeAsync(300);
    s.sent = [];

    // seq 3 arrives before seq 2 (out-of-order fan-out): read can only cover the gapless 2, but
    // this device has received up to 3. Both belong in the same flush.
    api.commit('alice', 'bob', 'm2');
    const m3 = api.commit('alice', 'bob', 'm3');
    s.receive({ type: 'message', ...m3 });
    s.receive({ type: 'message', ...api.messagesById.get(1)![1] });
    await vi.advanceTimersByTimeAsync(300);
    expect(s.sent).toEqual([{ type: 'read', conversationId: 1, seq: 3 }]);

    s.sent = [];
    const m5 = api.commit('alice', 'bob', 'm4') && api.commit('alice', 'bob', 'm5');
    s.receive({ type: 'message', ...m5 }); // m4 still in flight: read stays at 3
    await vi.advanceTimersByTimeAsync(300);
    expect(s.sent).toEqual([{ type: 'delivered', conversationId: 1, seq: 5 }]);
  });

  it("doesn't send read receipts for our own messages", async () => {
    const storage = memoryStorage();
    storage.setItem('chat.open.alice', 'bob');
    const { client, api, accept } = setup({ user: 'alice', storage });
    api.commit('bob', 'alice', 'hi alice');
    await client.start();
    const s = await accept();
    await vi.advanceTimersByTimeAsync(300);
    expect(s.sentOfType('read')).toEqual([{ type: 'read', conversationId: 1, seq: 1 }]);

    client.send('hi bob');
    const [frame] = s.sentOfType('send');
    s.receive({ type: 'ack', clientMsgId: frame.clientMsgId, id: 99, conversationId: 1, seq: 2, ts: 1 });
    await vi.advanceTimersByTimeAsync(300);
    expect(s.sentOfType('read')).toHaveLength(1);
  });

  it('keeps delivered when a read of an older seq is pending in the same flush', async () => {
    const storage = memoryStorage();
    storage.setItem('chat.open.bob', 'alice');
    const { client, api, accept } = setup({ storage });
    api.commit('alice', 'bob', 'm1');
    await client.start();
    const s = await accept();
    await vi.advanceTimersByTimeAsync(300);
    s.sent = [];

    // Within one 300ms window: seq 2 arrives (readable), then seq 4 before seq 3. Read can cover
    // only 2; delivered must still say 4. Sending just the read would drop the delivered for good.
    const [, m2, , m4] = ['m1', 'm2', 'm3', 'm4'].map((t, i) => (i === 0 ? null : api.commit('alice', 'bob', t)));
    s.receive({ type: 'message', ...m2! });
    s.receive({ type: 'message', ...m4! });
    await vi.advanceTimersByTimeAsync(300);
    expect(s.sent).toEqual([
      { type: 'read', conversationId: 1, seq: 2 },
      { type: 'delivered', conversationId: 1, seq: 4 },
    ]);
    expect(client.getSnapshot().chats[0].unread).toBe(0);
  });

  it('coalesces a burst into one delivered, and reads only while the tab is visible', async () => {
    const { client, api, accept } = setup();
    await client.start();
    const s = await accept();
    client.openChat('alice');
    client.setVisible(false);

    for (const t of ['a', 'b', 'c']) s.receive({ type: 'message', ...api.commit('alice', 'bob', t) });
    await settle();
    await vi.advanceTimersByTimeAsync(300);
    expect(s.sent).toEqual([{ type: 'delivered', conversationId: 1, seq: 3 }]);
    expect(client.getSnapshot().chats[0].unread).toBe(3);

    client.setVisible(true);
    await vi.advanceTimersByTimeAsync(300);
    expect(s.sentOfType('read')).toEqual([{ type: 'read', conversationId: 1, seq: 3 }]);
    expect(client.getSnapshot().chats[0].unread).toBe(0);
  });
});
