import { useReducer, type FormEvent } from 'react';
import { useChatClient, useChatState } from '../state/ChatContext';

type Mode = 'login' | 'register';

interface FormState {
  mode: Mode;
  username: string;
  password: string;
  pending: boolean;
  error: string | null;
}

type Action =
  | { type: 'field'; name: 'username' | 'password'; value: string }
  | { type: 'mode'; mode: Mode }
  | { type: 'submit' }
  | { type: 'failed'; error: string };

function reducer(state: FormState, action: Action): FormState {
  switch (action.type) {
    case 'field':
      return { ...state, [action.name]: action.value, error: null };
    case 'mode':
      return { ...state, mode: action.mode, error: null };
    case 'submit':
      return { ...state, pending: true, error: null };
    case 'failed':
      // The password is cleared after a failure, as login forms usually do; the username stays.
      return { ...state, pending: false, error: action.error, password: '' };
  }
}

const initial: FormState = { mode: 'login', username: '', password: '', pending: false, error: null };

export function LoginScreen() {
  const client = useChatClient();
  const { notice } = useChatState();
  const [state, dispatch] = useReducer(reducer, initial);
  const registering = state.mode === 'register';

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (state.pending) return;
    dispatch({ type: 'submit' });
    try {
      if (registering) await client.register(state.username, state.password);
      else await client.login(state.username, state.password);
      // On success this screen unmounts: the snapshot's auth flips to loggedIn.
    } catch (err) {
      // The server's messages are written for users ("Username is taken", the 401 that doesn't
      // say which of the two was wrong), so they're shown as they are.
      dispatch({ type: 'failed', error: err instanceof Error ? err.message : 'Something went wrong' });
    }
  }

  return (
    <div className="auth">
      <form className="auth-card" onSubmit={submit} noValidate>
        <h1 className="auth-title">
          <span className="auth-logo" aria-hidden="true">💬</span> Chat
        </h1>

        <div className="auth-tabs" role="tablist">
          {(['login', 'register'] as const).map((m) => (
            <button
              key={m}
              type="button"
              role="tab"
              aria-selected={state.mode === m}
              className={state.mode === m ? 'active' : ''}
              onClick={() => dispatch({ type: 'mode', mode: m })}
            >
              {m === 'login' ? 'Log in' : 'Create account'}
            </button>
          ))}
        </div>

        {notice && !state.error && <p className="auth-notice">{notice}</p>}

        <label>
          Username
          <input
            value={state.username}
            onChange={(e) => dispatch({ type: 'field', name: 'username', value: e.target.value })}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            autoFocus
            required
          />
        </label>
        {registering && <p className="hint">3–32 characters: a–z, 0–9 and _</p>}

        <label>
          Password
          <input
            type="password"
            value={state.password}
            onChange={(e) => dispatch({ type: 'field', name: 'password', value: e.target.value })}
            autoComplete={registering ? 'new-password' : 'current-password'}
            required
          />
        </label>
        {registering && <p className="hint">At least 8 characters</p>}

        {state.error && (
          <p className="auth-error" role="alert">
            {state.error}
          </p>
        )}

        <button
          className="primary"
          type="submit"
          disabled={state.pending || !state.username.trim() || !state.password}
        >
          {state.pending ? 'Please wait…' : registering ? 'Create account' : 'Log in'}
        </button>
      </form>
    </div>
  );
}
