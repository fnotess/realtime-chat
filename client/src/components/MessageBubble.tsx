import { memo } from 'react';
import type { Tick } from '../chat/ChatClient';
import { formatTime } from '../format';
import { Ticks } from './Ticks';

interface Props {
  mine: boolean;
  text: string;
  ts: number;
  tick: Tick | null;
  error?: string;
}

// Primitive props, so memo skips every bubble whose content and tick didn't change: a snapshot
// is rebuilt on each event, but only the bubbles that differ re-render.
export const MessageBubble = memo(function MessageBubble({ mine, text, ts, tick, error }: Props) {
  return (
    <div className={`row ${mine ? 'mine' : 'theirs'}`}>
      <div className={`bubble${tick === 'failed' ? ' failed' : ''}`}>
        {/* Plain text child: React escapes it, so message text can never become markup. */}
        <p className="text">{text}</p>
        <span className="meta">
          {formatTime(ts)}
          {tick && <Ticks tick={tick} />}
        </span>
      </div>
      {error && <p className="bubble-error">{error}</p>}
    </div>
  );
});
