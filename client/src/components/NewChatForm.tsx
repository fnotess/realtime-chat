import { useState, type FormEvent } from 'react';
import { USERNAME_PATTERN, normalizeUsername } from '../chat/protocol';
import { useChatClient, useChatState } from '../state/ChatContext';

// There is deliberately no "does this user exist?" endpoint: it would be a free username
// enumeration API. Format mistakes are caught here; a name that is well-formed but unknown comes
// back as unknown_recipient on the first send and shows on that message.
export function NewChatForm() {
  const client = useChatClient();
  const { me } = useChatState();
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const other = normalizeUsername(name);
    if (!other) return;
    if (!USERNAME_PATTERN.test(other)) {
      setError('Usernames are 3–32 characters: a–z, 0–9 and _');
      return;
    }
    if (other === me) {
      setError("That's you. Pick someone else to chat with.");
      return;
    }
    client.openChat(other);
    setName('');
    setError(null);
  }

  return (
    <form className="new-chat" onSubmit={submit}>
      <input
        value={name}
        onChange={(e) => {
          setName(e.target.value);
          setError(null);
        }}
        placeholder="New chat: username"
        aria-label="Start a chat with username"
        aria-invalid={error !== null}
        autoCapitalize="none"
        spellCheck={false}
      />
      <button type="submit" disabled={!name.trim()}>
        Chat
      </button>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
