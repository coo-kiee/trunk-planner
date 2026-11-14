import type { ReactNode } from 'react';
import { TONE_TOKENS, type StatusTone } from './status-tones.ts';
import styles from './ui.module.css';

interface StatusProps {
  tone: StatusTone;
  icon: string;
  label: string;
  children?: ReactNode;
  /** Stable state name for tests and evidence. */
  state?: string;
  live?: boolean;
}

export function Status({ tone, icon, label, children, state, live }: StatusProps) {
  return (
    <div className={styles.status} data-ds="status" data-tone={tone} data-token={TONE_TOKENS[tone]} data-state={state} role={live ? 'status' : undefined}>
      <span className={styles.statusIcon} aria-hidden="true">{icon}</span>
      <div className={styles.statusBody}>
        <p className={styles.statusLabel}>{label}</p>
        {children ? <div className={styles.statusDetail}>{children}</div> : null}
      </div>
    </div>
  );
}
