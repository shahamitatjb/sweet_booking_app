'use client';

import { useCallback, useRef, useState } from 'react';

export type ToastState = { text: string; tone: 'ok' | 'err' } | null;

const TOAST_MS = 4000;

export function useToast() {
  const [toast, setToast] = useState<ToastState>(null);
  const timer = useRef<number | undefined>(undefined);
  const show = useCallback((text: string, tone: 'ok' | 'err' = 'ok') => {
    window.clearTimeout(timer.current);
    setToast({ text, tone });
    timer.current = window.setTimeout(() => setToast(null), TOAST_MS);
  }, []);
  return { toast, show };
}

export function Toast({ toast }: { toast: ToastState }) {
  if (!toast) return null;
  return (
    <div className={`toast ${toast.tone} no-print`} role="status" aria-live="polite">
      {toast.text}
    </div>
  );
}
