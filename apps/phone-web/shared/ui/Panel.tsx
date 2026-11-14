import type { ReactNode } from 'react';
import styles from './ui.module.css';

interface PanelProps {
  title?: string;
  level?: 2 | 3;
  children: ReactNode;
  testId?: string;
}

export function Panel({ title, level = 2, children, testId }: PanelProps) {
  const Heading = level === 2 ? 'h2' : 'h3';
  return (
    <section className={styles.panel} data-ds="panel" data-testid={testId}>
      {title ? <Heading className={styles.panelTitle} data-level={level}>{title}</Heading> : null}
      {children}
    </section>
  );
}
