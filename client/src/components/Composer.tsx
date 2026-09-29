import { useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { MAX_TEXT_CHARS } from '../chat/protocol';
import { useChatClient } from '../state/ChatContext';

const MAX_ROWS_PX = 160;

export function Composer() {
  const client = useChatClient();
  const [text, setText] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);
  const tooLong = text.length > MAX_TEXT_CHARS;
  const canSend = text.trim().length > 0 && !tooLong;

  // Grow with the text up to a cap, then scroll inside. Reset to auto first, or it could only grow.
  useLayoutEffect(() => {
    const el = ref.current!;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, MAX_ROWS_PX)}px`;
  }, [text]);

  function send() {
    if (!canSend) return;
    // Works offline too: the message waits in the outbox with a 🕓 until the socket is back.
    client.send(text);
    setText('');
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    // isComposing: Enter that confirms an IME candidate (Chinese, Japanese, Korean input) must not
    // also send the half-typed message.
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send();
    }
  }

  return (
    <form
      className="composer"
      onSubmit={(e) => {
        e.preventDefault();
        send();
      }}
    >
      <textarea
        ref={ref}
        rows={1}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder="Type a message"
        aria-label="Message"
        autoFocus
      />
      {text.length > MAX_TEXT_CHARS - 200 && (
        <span className={`counter${tooLong ? ' over' : ''}`}>{MAX_TEXT_CHARS - text.length}</span>
      )}
      <button type="submit" className="send" disabled={!canSend} aria-label="Send">
        ➤
      </button>
    </form>
  );
}
