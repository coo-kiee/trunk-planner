import type { ButtonHTMLAttributes } from 'react';
import styles from './ui.module.css';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'step';
}

export function Button({ variant = 'secondary', type = 'button', ...rest }: ButtonProps) {
  return <button {...rest} type={type} className={styles.button} data-ds="button" data-variant={variant} />;
}
