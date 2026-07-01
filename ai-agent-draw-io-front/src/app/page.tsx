'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUserInfo } from '@/utils/cookie';
import { getWorkspaceIdentity } from '@/utils/workspace-identity';

export default function Home() {
  const router = useRouter();

  useEffect(() => {
    const userInfo = getUserInfo();
    getWorkspaceIdentity(userInfo?.user);
    router.replace('/drawio');
  }, [router]);

  return null;
}
