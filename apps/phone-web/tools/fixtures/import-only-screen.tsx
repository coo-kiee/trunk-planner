// Negative fixture for the DS check: imports shared UI without ever rendering it. Must be reported.
import { Button } from '../../shared/ui/Button.tsx';
import { Status } from '../../shared/ui/Status.tsx';

export const used = [Button, Status].length;
export function ImportOnlyScreen() {
  return <p>nothing from the design system is rendered here</p>;
}
