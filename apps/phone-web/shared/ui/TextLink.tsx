import type { ReactNode } from 'react';
import styles from './ui.module.css';

/** Same-site documents only; a plain anchor keeps the no-referrer policy in force. */
export function TextLink({ href, children }: { href: `/${string}`; children: ReactNode }) {
  return <a className={styles.link} href={href} data-ds="link">{children}</a>;
}
