// Negative fixture for the DS check: must be reported. Never imported by the app.
import { Button } from '../../shared/ui/Button.tsx';

export function BypassScreen() {
  return (
    <div>
      <button style={{ color: '#ff0000' }}>raw button</button>
      <input name="raw" />
      <Button>real</Button>
    </div>
  );
}
