import { Fragment, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { OpenChatView } from '../chat/ChatClient';
import { formatDay, sameDay } from '../format';
import { useChatClient } from '../state/ChatContext';
import { MessageBubble } from './MessageBubble';

/** Within this many pixels of the bottom counts as "reading the latest". */
const NEAR_BOTTOM_PX = 80;
/** Start fetching the older page before the very top, so scrolling rarely has to wait. */
const LOAD_OLDER_PX = 200;

export function MessageList({ view }: { view: OpenChatView }) {
  const client = useChatClient();
  const ref = useRef<HTMLDivElement>(null);
  const nearBottom = useRef(true);
  const prev = useRef<{ first?: string; last?: string; height: number } | null>(null);
  const [newBelow, setNewBelow] = useState(false);

  const { messages } = view;
  const first = messages[0]?.key;
  const last = messages[messages.length - 1]?.key;

  // Layout effect: the scroll position has to be fixed before the browser paints, or a prepended
  // page would visibly jump and then snap back.
  useLayoutEffect(() => {
    const el = ref.current!;
    const p = prev.current;
    if (!p) {
      el.scrollTop = el.scrollHeight;
    } else if (first !== p.first && last === p.last) {
      // An older page went in above. Shift by exactly the height it added, so the message the
      // user was looking at stays where it was.
      el.scrollTop += el.scrollHeight - p.height;
    } else if (last !== p.last) {
      const newest = messages[messages.length - 1];
      // Follow new messages only if the user was already at the bottom, or it's their own new
      // message; someone reading back through history isn't yanked down.
      if (nearBottom.current || (newest?.mine && newest.tick === 'pending')) {
        el.scrollTop = el.scrollHeight;
      } else if (!newest?.mine) {
        setNewBelow(true);
      }
    }
    prev.current = { first, last, height: el.scrollHeight };
  });

  // A first page shorter than the pane has no scrollbar, so no scroll event would ever ask for
  // older messages. Keep loading until the pane is full or there's nothing older.
  useEffect(() => {
    const el = ref.current!;
    if (view.hasOlder && !view.loadingOlder && el.scrollHeight <= el.clientHeight) void client.loadOlder();
  }, [client, view.hasOlder, view.loadingOlder, messages.length]);

  function onScroll() {
    const el = ref.current!;
    nearBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX;
    if (nearBottom.current) setNewBelow(false);
    if (el.scrollTop < LOAD_OLDER_PX && view.hasOlder && !view.loadingOlder) void client.loadOlder();
  }

  function jumpToLatest() {
    const el = ref.current!;
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    setNewBelow(false);
  }

  return (
    <div className="messages-wrap">
      {view.loadingOlder && <div className="loading-older">Loading older messages…</div>}
      <div className="messages" ref={ref} onScroll={onScroll} role="log">
        {view.loading && messages.length === 0 && <p className="messages-empty">Loading…</p>}
        {!view.loading && messages.length === 0 && (
          <p className="messages-empty">No messages yet. Say hello to {view.other} 👋</p>
        )}
        {messages.map((m, i) => (
          <Fragment key={m.key}>
            {(i === 0 || !sameDay(messages[i - 1].ts, m.ts)) && (
              <div className="day">
                <span>{formatDay(m.ts)}</span>
              </div>
            )}
            <MessageBubble mine={m.mine} text={m.text} ts={m.ts} tick={m.tick} error={m.error} />
          </Fragment>
        ))}
      </div>
      {newBelow && (
        <button className="jump" onClick={jumpToLatest}>
          New messages ↓
        </button>
      )}
    </div>
  );
}
