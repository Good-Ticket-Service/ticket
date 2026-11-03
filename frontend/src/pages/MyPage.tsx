import { ErrorNotice, Loading, Title } from '../components/Common';
import { Facts, Panel } from '../components/Form';
import { ROLE_LABEL, useMe } from '../components/Layout';
import { formatDateTime } from '../lib/format';

/** 정보 수정·탈퇴는 명세가 초안이라 조회만 한다. */
export function MyPage() {
  const me = useMe();
  if (me.isPending) return <main className="content narrow"><Loading /></main>;
  if (me.isError) return <main className="content narrow"><ErrorNotice error={me.error} /></main>;
  const user = me.data;
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Title en="My Info" ko="내 정보" />
      <Panel title={user.name}>
        <Facts
          items={[
            ['회원 ID', user.userId],
            ['이메일', user.email],
            ['휴대폰', user.phone],
            ['역할', ROLE_LABEL[user.role]],
            ['상태', user.status],
            ['가입', formatDateTime(user.createdAt)],
          ]}
        />
      </Panel>
    </main>
  );
}
