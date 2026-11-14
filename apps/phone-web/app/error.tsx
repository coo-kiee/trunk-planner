'use client';
// Last line of defence for a rendering failure: Korean guidance instead of the framework's page, with saved data left alone.
import { Button } from '../shared/ui/Button.tsx';
import { Panel } from '../shared/ui/Panel.tsx';
import { Status } from '../shared/ui/Status.tsx';
import styles from '../screens/shell/shell.module.css';

export default function ErrorScreen({ reset }: { reset: () => void }) {
  return (
    <main className={styles.page} data-screen="error">
      <Panel title="화면을 표시하지 못했습니다">
        <Status tone="conflict" icon="!" label="일시적인 화면 오류입니다" state="render-error" live>
          이 기기에 저장된 내용은 지우지 않았습니다. 다시 시도해도 같으면 앱을 닫았다가 다시 여세요.
        </Status>
        <Button variant="primary" onClick={reset}>다시 시도</Button>
      </Panel>
    </main>
  );
}
