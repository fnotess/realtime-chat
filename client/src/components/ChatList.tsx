import { useChatClient, useChatState } from '../state/ChatContext';
import { formatListTime } from '../format';
import { Avatar } from './Avatar';
import { Ticks } from './Ticks';

export function ChatList() {
  const client = useChatClient();
  const { chats, open } = useChatState();

  if (chats.length === 0) {
    return <p className="chat-list-empty">No chats yet. Start one above.</p>;
  }

  return (
    <ul className="chat-list">
      {chats.map((c) => (
        <li key={c.other}>
          <button
            className={`chat-item${open?.other === c.other ? ' active' : ''}`}
            onClick={() => client.openChat(c.other)}
            aria-current={open?.other === c.other ? 'true' : undefined}
          >
            <Avatar name={c.other} />
            <span className="chat-item-body">
              <span className="chat-item-top">
                <span className="chat-item-name">{c.other}</span>
                {c.ts !== null && (
                  <span className={`chat-item-time${c.unread ? ' unread' : ''}`}>{formatListTime(c.ts)}</span>
                )}
              </span>
              <span className="chat-item-bottom">
                <span className="chat-item-preview">
                  {c.preview?.tick && <Ticks tick={c.preview.tick} />}
                  {c.preview ? c.preview.text : <em>No messages yet</em>}
                </span>
                {c.unread > 0 && (
                  <span className="badge" aria-label={`${c.unread} unread`}>
                    {c.unread > 99 ? '99+' : c.unread}
                  </span>
                )}
              </span>
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
