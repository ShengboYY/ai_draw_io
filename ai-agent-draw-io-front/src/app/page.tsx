'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUserInfo } from '@/utils/cookie';

export default function Home() {
  const router = useRouter();

  useEffect(() => {
    const userInfo = getUserInfo();
    router.replace(userInfo?.user ? '/drawio' : '/login');
  }, [router]);

  return null;
}
