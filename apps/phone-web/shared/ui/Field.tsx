// Native inputs under one accessible wrapper: label, hint and error are always programmatically connected.
import { useId, type ReactNode } from 'react';
import styles from './ui.module.css';

interface Shared {
  label: string;
  hint?: ReactNode;
  error?: string | undefined;
  disabled?: boolean;
  name: string;
}
interface TextField extends Shared { kind: 'text' | 'integer'; value: string; onChange: (value: string) => void; maxLength?: number }
interface SelectField extends Shared { kind: 'select'; value: string; onChange: (value: string) => void; options: ReadonlyArray<{ value: string; label: string }>; placeholder: string }
interface CheckboxField extends Shared { kind: 'checkbox'; checked: boolean; onChange: (checked: boolean) => void }
interface FileField extends Shared { kind: 'file'; accept: string; onSelect: (file: File | null) => void }
type FieldProps = TextField | SelectField | CheckboxField | FileField;

export function Field(props: FieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [props.hint ? hintId : null, props.error ? errorId : null].filter(Boolean).join(' ') || undefined;
  const aria = { 'aria-describedby': describedBy, 'aria-invalid': props.error ? true : undefined } as const;
  const common = { id, name: props.name, disabled: props.disabled, ...aria };

  const notes = (
    <>
      {props.hint ? <p id={hintId} className={styles.hint}>{props.hint}</p> : null}
      {props.error ? <p id={errorId} className={styles.error} role="alert">{props.error}</p> : null}
    </>
  );

  if (props.kind === 'checkbox') {
    return (
      <div className={styles.field} data-ds="field" data-kind="checkbox" data-invalid={props.error ? 'true' : undefined}>
        <label htmlFor={id} className={styles.checkRow}>
          <input {...common} className={styles.check} type="checkbox" checked={props.checked} onChange={(event) => props.onChange(event.target.checked)} />
          <span className={styles.label}>{props.label}</span>
        </label>
        {notes}
      </div>
    );
  }

  return (
    <div className={styles.field} data-ds="field" data-kind={props.kind} data-invalid={props.error ? 'true' : undefined}>
      <label htmlFor={id} className={styles.label}>{props.label}</label>
      {props.kind === 'select' ? (
        <select {...common} className={styles.control} value={props.value} onChange={(event) => props.onChange(event.target.value)}>
          <option value="">{props.placeholder}</option>
          {props.options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      ) : props.kind === 'file' ? (
        <input
          {...common}
          className={styles.control}
          type="file"
          accept={props.accept}
          onChange={(event) => {
            props.onSelect(event.target.files?.[0] ?? null);
            event.target.value = ''; // The same file can be chosen again after an error.
          }}
        />
      ) : (
        <input
          {...common}
          className={styles.control}
          type="text"
          inputMode={props.kind === 'integer' ? 'numeric' : 'text'}
          autoComplete="off"
          value={props.value}
          onChange={(event) => props.onChange(event.target.value)}
        />
      )}
      {notes}
    </div>
  );
}
