import { api } from './api';
import type { SessionConfig, StoryMessage, TurnOptions } from './api';

export interface TurnInput {
  sessionId: string;
  prompt: string;
  image?: File;
  signal?: AbortSignal;
  pastMessageIndexes?: number[];
  onPrompt: (prompt: string) => void;
  onStreamPrompt: (prompt: string) => void;
  onStreamText: (text: string | null) => void;
  onMessages: (messages: StoryMessage[]) => void;
  onReferenceBefore?: (referenceBeforeMessageIndex: number) => void;
  // When set, the refreshed history is read as the most recent page so a long story never re-enters the whole DOM.
  historyLimit?: number;
  onHasOlder?: (hasOlder: boolean) => void;
}

// The narrow API surface submitTurn relies on, so tests can drive the real logic without monkey-patching.
export interface TurnApi {
  config: (id: string) => Promise<SessionConfig>;
  turn: (id: string, prompt: string, options?: TurnOptions) => Promise<unknown>;
  streamTurn: (id: string, prompt: string, onText: (text: string) => void, options?: TurnOptions) => Promise<void>;
  history: (id: string, page?: { limit?: number }) =>
    Promise<{ sessionId: string; messages: StoryMessage[]; referenceBeforeMessageIndex?: number; hasOlder?: boolean }>;
}

// Keep the submitted prompt until the turn truly finished, and clear it before rereading history.
export async function submitTurn(input: TurnInput, dependencies: TurnApi = api): Promise<void> {
  input.onStreamText(null);
  const config = await dependencies.config(input.sessionId);
  if (config.effectiveStreamingEnabled) {
    input.onStreamPrompt(input.prompt.trim());
    input.onStreamText('');
    let receivedText = '';
    try {
      await dependencies.streamTurn(input.sessionId, input.prompt.trim(), text => {
        receivedText = text;
        input.onStreamText(text);
      }, { image: input.image, signal: input.signal, pastMessageIndexes: input.pastMessageIndexes });
    } catch (cause) {
      if (!receivedText.trim()) input.onStreamText(null);
      throw cause;
    }
  } else {
    await dependencies.turn(input.sessionId, input.prompt.trim(),
      { image: input.image, signal: input.signal, pastMessageIndexes: input.pastMessageIndexes });
  }
  input.onPrompt('');
  const page = input.historyLimit === undefined ? undefined : { limit: input.historyLimit };
  const history = await dependencies.history(input.sessionId, page);
  input.onMessages(history.messages);
  if (typeof history.referenceBeforeMessageIndex === 'number') {
    input.onReferenceBefore?.(history.referenceBeforeMessageIndex);
  }
  if (typeof history.hasOlder === 'boolean') {
    input.onHasOlder?.(history.hasOlder);
  }
  input.onStreamText(null);
}
