import { Panel } from '../../shared/ui/Panel.tsx';
import { TextLink } from '../../shared/ui/TextLink.tsx';
import styles from '../shell/shell.module.css';

/** Plain-language notice. Retention figures are the relay's provisional configuration values. */
export function PrivacyNotice() {
  return (
    <main className={styles.page} data-screen="privacy">
      <Panel title="개인정보와 전송 안내">
        <ul className={styles.list}>
          <li>기본으로 보내는 내용은 물품 이름, 치수, 오차, 취급조건입니다. 이름에 개인정보를 적지 마세요.</li>
          <li>사진은 따로 동의한 경우에만 보냅니다. 보내기 전에 크기를 줄이고 다시 저장해 위치·촬영 정보와 원래 파일 이름을 없앱니다.</li>
          <li>내용은 전송 서버에 잠시 보관되었다가 차량이 처리하면 지워집니다. 초안 보관은 최대 24시간, 연결은 최대 24시간입니다(시험 중인 잠정값).</li>
          <li>종단간 암호화가 아닙니다. 전송 서버 운영자는 보관 중인 내용을 볼 수 있습니다.</li>
          <li>이 기기에는 작성 내용, 줄인 사진, 연결 정보가 브라우저 저장소에 남습니다. 저장소 암호화는 보장하지 않습니다. 휴대폰을 잃어버렸다면 차량에서 연결을 해제하세요.</li>
          <li>차량에 반영하려면 차량 사용자의 수락이 필요합니다. 전송 서버는 트렁크 배치나 차량을 바꿀 수 없습니다.</li>
        </ul>
        <TextLink href="/">물품 등록으로 돌아가기</TextLink>
      </Panel>
    </main>
  );
}
