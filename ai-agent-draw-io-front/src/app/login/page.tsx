'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { setUserInfo, getUserInfo, clearUserInfo } from '@/utils/cookie';

export default function Login() {
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [msg, setMsg] = useState({ text: '', type: '' });
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [currentUser, setCurrentUser] = useState('');

  useEffect(() => {
    const userInfo = getUserInfo();
    if (userInfo && userInfo.user) {
      // Draw.io is the only enabled workspace for now.
      router.push('/drawio');
    }
  }, [router]);

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault();
    setMsg({ text: '', type: '' });

    if (!username || !password) {
      setMsg({ text: 'Please enter username and password.', type: 'error' });
      return;
    }

    if (username !== 'admin' || password !== 'admin') {
      setMsg({ text: 'Incorrect username or password. Demo account: admin / admin.', type: 'error' });
      return;
    }

    setUserInfo(username);
    setMsg({ text: 'Login successful. Redirecting...', type: 'info' });
    setTimeout(() => {
      router.push('/drawio');
    }, 500);
  };

  const handleFillDemo = () => {
    setUsername('admin');
    setPassword('admin');
    setMsg({ text: 'Demo account filled.', type: 'info' });
  };

  const handleLogout = () => {
    clearUserInfo();
    setIsLoggedIn(false);
    setCurrentUser('');
    setMsg({ text: 'Logged out. Cookie cleared.', type: 'info' });
  };

  return (
    <div className="min-h-screen flex justify-center items-stretch p-7 theme-bg-gradient">
      <div className="w-full max-w-[1120px] grid grid-cols-1 lg:grid-cols-[1.25fr_0.75fr] gap-[18px]">
        {/* Hero Section */}
        <section className="theme-card rounded-[18px] overflow-hidden relative flex flex-col gap-[18px] p-[28px_28px_22px_28px]">
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-[14px] grid place-items-center bg-gradient-to-br from-[#62f6c7] to-[#5aa9ff] shadow-[0_10px_24px_rgba(0,0,0,0.4)] text-[rgba(7,10,18,0.92)] font-extrabold text-lg tracking-[0.5px]">
              AI
            </div>
            <div className="flex flex-col gap-1">
              <strong className="text-base leading-[1.1] tracking-[0.2px] text-[rgba(255,255,255,0.92)]">
                AI Agent Workspace</strong>
              <span className="text-xs text-[rgba(255,255,255,0.56)]">Build faster · Run reliably · Operate clearly</span>
            </div>
          </div>

          <h1 className="mt-[6px] text-[30px] leading-[1.2] tracking-[0.2px] text-[rgba(255,255,255,0.92)] font-bold">
            An AI workspace for getting diagrams done
          </h1>
          <p className="m-0 text-[rgba(255,255,255,0.72)] leading-[1.7] max-w-[52ch] text-sm">
            The left side shows agent capabilities and the right side handles login. Demo login:
            username <b>admin</b>, password <b>admin</b>. After login, a browser cookie is saved.
          </p>

          <div className="grid grid-cols-2 gap-3 mt-[6px]">
            {[
              { title: 'Tool Calling', desc: 'Orchestrates API, shell, file, and tool execution paths' },
              { title: 'Memory & Context', desc: 'Configurable and auditable context for fewer repeated instructions' },
              { title: 'Multi-Model Routing', desc: 'Selects suitable models and strategies by scenario' },
              { title: 'Observability', desc: 'Tracks execution paths, cost, and failure causes' },
            ].map((item, idx) => (
              <div key={idx} className="border border-[rgba(255,255,255,0.08)] bg-[rgba(255,255,255,0.04)] rounded-[14px] p-3 flex gap-[10px] items-start">
                <div className="w-[10px] h-[10px] rounded-full mt-[5px] flex-shrink-0 bg-gradient-to-br from-[#62f6c7] to-[#5aa9ff] shadow-[0_0_0_4px_rgba(98,246,199,0.08)]"></div>
                <div>
                  <b className="block text-[13px] mb-[3px] text-[rgba(255,255,255,0.92)]">{item.title}</b>
                  <span className="block text-xs text-[rgba(255,255,255,0.56)] leading-[1.5]">{item.desc}</span>
                </div>
              </div>
            ))}
          </div>

          <div className="mt-[10px] rounded-[16px] overflow-hidden border border-[rgba(255,255,255,0.10)] bg-[rgba(0,0,0,0.24)] h-[340px] relative">
             {/* Placeholder for Hero Image - mimicking the original svg placeholder */}
             <div className="w-full h-full flex items-center justify-center text-[rgba(255,255,255,0.2)] text-sm">
                AI Agent Preview
             </div>
          </div>
        </section>

        {/* Login Form Section */}
        <section className="p-[28px] flex flex-col justify-center gap-[14px]">
          <div className="theme-card rounded-[16px] p-5">
            <h2 className="m-0 mb-[6px] text-[18px] text-[rgba(255,255,255,0.92)] font-bold">Login</h2>
            <p className="m-0 mb-4 text-[rgba(255,255,255,0.56)] text-xs leading-[1.5]">
              Demo account: admin / admin. Replace this page logic with real authentication for production.
            </p>

            {!isLoggedIn ? (
              <form onSubmit={handleLogin} autoComplete="on">
                <div className="flex flex-col gap-2 mb-3">
                  <label htmlFor="username" className="text-xs text-[rgba(255,255,255,0.72)] tracking-[0.2px]">Username</label>
                  <input
                    id="username"
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="Enter username"
                    autoComplete="username"
                    className="w-full rounded-[12px] theme-input p-3 outline-none transition-all duration-180 text-sm"
                  />
                </div>

                <div className="flex flex-col gap-2 mb-3">
                  <label htmlFor="password" className="text-xs text-[rgba(255,255,255,0.72)] tracking-[0.2px]">Password</label>
                  <input
                    id="password"
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter password"
                    autoComplete="current-password"
                    className="w-full rounded-[12px] theme-input p-3 outline-none transition-all duration-180 text-sm"
                  />
                </div>

                <div className="flex gap-[10px] items-center justify-between mt-[6px]">
                  <button type="submit" className="theme-btn rounded-[12px] p-[11px_14px] font-bold cursor-pointer border-0 transition-transform active:translate-y-[1px] active:brightness-[0.98] text-sm">
                    Login and Save Cookie
                  </button>
                  <button type="button" onClick={handleFillDemo} className="theme-btn-secondary rounded-[12px] p-[11px_14px] font-semibold cursor-pointer transition-transform active:translate-y-[1px] active:brightness-[0.98] text-sm">
                    Fill Demo Account
                  </button>
                </div>
              </form>
            ) : (
              <div className="flex gap-[10px] items-center justify-between p-3 border border-dashed border-[rgba(255,255,255,0.18)] rounded-[12px] bg-[rgba(255,255,255,0.04)] mt-3">
                <div>
                  <strong className="block text-[13px] text-[rgba(255,255,255,0.92)]">Logged in: {currentUser}</strong>
                  <span className="block text-xs text-[rgba(255,255,255,0.56)] mt-[2px]">Welcome back</span>
                </div>
                <button onClick={handleLogout} className="theme-btn-secondary rounded-[12px] p-[8px_12px] font-semibold cursor-pointer text-xs">
                  Logout
                </button>
              </div>
            )}

            <div className={`min-h-[18px] text-xs mt-2 ${msg.type === 'error' ? 'text-[#ff5a7a]' : 'text-[rgba(255,255,255,0.56)]'}`}>
              {msg.text}
            </div>
          </div>

          <div className="mt-[14px] text-[rgba(255,255,255,0.35)] text-xs text-center">
            © AI Draw.io Builder · Next.js demo page
          </div>
        </section>
      </div>
    </div>
  );
}
