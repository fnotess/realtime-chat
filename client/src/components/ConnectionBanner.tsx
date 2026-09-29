import { useEffect, useState } from 'react';
import { useChatClient, useChatState } from '../state/ChatContext';

export function ConnectionBanner() {
  const client = useChatClient();
  const { connection, browserOnline, outboxSize } = useChatState();
  const now = useNow(connection.state === 'waiting');

  let text: string | null = null;
  let retry = false;
  if (!browserOnline) {
    text = 'Offline, messages will send when back';
  } else if (connection.state === 'waiting') {
    const seconds = Math.max(0, Math.ceil((connection.retryAt - now) / 1000));
    text = seconds > 0 ? `Reconnecting in ${seconds}s` : 'Reconnecting…';
    retry = seconds > 0;
  } else if (connection.state === 'connecting') {
    text = 'Connecting…';
  }
  if (!text) return null;

  const queued = outboxSize > 0 && browserOnline ? ` · ${outboxSize} waiting to send` : '';
  return (
    <div className="banner" role="status">
      {text}
      {queued}
      {retry && (
        <button className="link" onClick={() => client.reconnectNow()}>
          Retry now
        </button>
      )}
    </div>
  );
}

/** The current time, re-rendering every half second while `active`, for the countdown. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (!active) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(id);
  }, [active]);
  return now;
}
