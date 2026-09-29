// Wire types, mirroring the server's JSON (see CLAUDE.md "Protocol"). History messages and live
// "message" frames share one shape on purpose, so both go through the same merge path.

export interface Message {
  id: number;
  conversationId: number;
  seq: number;
  from: string;
  to: string;
  text: string;
  clientMsgId: string;
  ts: number;
}

export interface ConversationSummary {
  id: number;
  otherUser: string;
  lastSeq: number;
  lastSender: string | null;
  lastBody: string | null;
  lastAt: string | number | null;
  otherDeliveredSeq: number;
  otherReadSeq: number;
  unreadCount: number;
}

export interface HistoryPage {
  messages: Message[];
  nextBeforeSeq: number | null;
  hasMore: boolean;
}

export type ServerFrame =
  | { type: 'ready'; user: string }
  | ({ type: 'message' } & Message)
  | { type: 'ack'; clientMsgId: string; id: number; conversationId: number; seq: number; ts: number }
  | { type: 'receipt'; conversationId: number; user: string; deliveredSeq: number; readSeq: number }
  | { type: 'error'; code: string; message: string; clientMsgId?: string };

export type ClientFrame =
  | { type: 'send'; to: string; clientMsgId: string; text: string }
  | { type: 'delivered' | 'read'; conversationId: number; seq: number };

/** Close code the server uses when the session ended (logout elsewhere, expiry). Never retried. */
export const SESSION_ENDED = 4001;

/** MessageRouter.MAX_TEXT_CHARS. Java's String.length() and JS's both count UTF-16 units. */
export const MAX_TEXT_CHARS = 4000;

/** Usernames.VALID on the server, so the UI can reject a bad "New chat" name before any send. */
export const USERNAME_PATTERN = /^[a-z0-9_]{3,32}$/;

export function normalizeUsername(raw: string): string {
  return raw.trim().toLowerCase();
}
