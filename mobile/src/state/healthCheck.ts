import { create } from 'zustand';

import type {
  Answers,
  HealthCheckPreferences,
  HealthCheckQuestion,
  HealthCheckResult,
} from '@/api/healthCheck';

/**
 * The health check in progress (HealthCheck*.dc.html; D55). Kept in memory only: answers are
 * health data and are saved to Core as the person goes, sealed there.
 */
type HealthCheckState = {
  responseId: string | null;
  token: string | null;
  questions: HealthCheckQuestion[];
  priceBands: number[];
  answers: Answers;
  next: string[];
  safetySeen: boolean;
  preferences: HealthCheckPreferences;
  result: HealthCheckResult | null;
  begin: (input: {
    responseId: string;
    token: string | null;
    questions: HealthCheckQuestion[];
    priceBands: number[];
  }) => void;
  answer: (code: string, values: string[]) => void;
  setNext: (next: string[]) => void;
  setPreferences: (p: HealthCheckPreferences) => void;
  finish: (result: HealthCheckResult) => void;
  reset: () => void;
};

const empty = {
  responseId: null,
  token: null,
  questions: [],
  priceBands: [],
  answers: {},
  next: [],
  safetySeen: false,
  preferences: {},
  result: null,
};

export const useHealthCheck = create<HealthCheckState>()((set) => ({
  ...empty,
  begin: ({ responseId, token, questions, priceBands }) =>
    set({
      ...empty,
      responseId,
      token,
      questions,
      priceBands,
      next: questions.filter((q) => !q.showIf).map((q) => q.code),
    }),
  answer: (code, values) => set((s) => ({ answers: { ...s.answers, [code]: values } })),
  setNext: (next) => set({ next }),
  setPreferences: (preferences) => set({ preferences }),
  finish: (result) => set({ result }),
  reset: () => set(empty),
}));

/** The question to show now: the first one Core says is still to answer. */
export function currentQuestion(state: Pick<HealthCheckState, 'questions' | 'next'>) {
  const code = state.next[0];
  return state.questions.find((q) => q.code === code) ?? null;
}
