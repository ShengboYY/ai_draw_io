'use client';

import { useEffect, useState } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { agentApi } from '@/api/agent';
import { AdminAccountMenu } from '@/app/admin/admin-account-menu';
import { getUserInfo } from '@/utils/cookie';

// Brand accent from the Free Draw Redesign spec.
const ACCENT = '#34333b';
const DISPLAY = 'var(--font-display)';
const MONO = 'var(--font-mono)';
// Align desktop content with the OpenAI API page's measured outer gutters.
const PAGE_MAX_WIDTH = 1440;
const HOME_INTRO_SESSION_KEY = 'freedraw_home_intro_seen';

// Palette used by the product-preview canvas art (mirrors the design's PAL).
const PAL = {
  blue: '#e3e5e9',
  green: '#edefee',
  purple: '#dcdce0',
  amber: '#f1efeb',
  red: '#e9e7e6',
};

const HERO_EXAMPLES = [
  { label: 'User login flowchart', prompt: 'Please help me draw a user login flowchart' },
  { label: 'Microservices architecture', prompt: 'Draw a microservices system architecture' },
  { label: 'Login sequence diagram', prompt: 'Draw a sequence diagram for login' },
  { label: 'Database ER diagram', prompt: 'Draw an ER diagram for e-commerce orders' },
];

// Public labels for the diagram shapes currently covered by built-in drawio-* skills.
const DIAGRAM_TYPES = ['Flowchart', 'Architecture', 'UML Class', 'Sequence', 'ER Diagram', 'Use Case', 'State', 'Concept Map'];

// Homepage feature copy reflects the product's supported creation, import, and skill workflows.
const FEATURES = [
  {
    title: 'Natural-language generation',
    body: 'Describe the diagram you want in plain words — the Agent lays it out, connects and styles it in seconds.',
    icon: (
      <svg viewBox="0 0 24 24" width={22} height={22} fill="none">
        <path d="M4 6h16M4 12h10M4 18h13" stroke="currentColor" strokeWidth={2} strokeLinecap="round" />
      </svg>
    ),
  },
  {
    title: 'Multimodal import & creation',
    body: 'Import PDFs and images, then ask the Agent to understand their text, tables and diagrams, answer questions, summarize key points or turn the source into an editable diagram.',
    icon: (
      <svg viewBox="0 0 24 24" width={22} height={22} fill="none">
        <path d="M6 3.5h8l4 4v12A1.5 1.5 0 0 1 16.5 21h-9A1.5 1.5 0 0 1 6 19.5v-14A2 2 0 0 1 6 3.5Z" stroke="currentColor" strokeWidth={2} strokeLinejoin="round" />
        <path d="M14 3.5v4h4" stroke="currentColor" strokeWidth={2} strokeLinejoin="round" />
        <circle cx={10} cy={11} r={1.1} fill="currentColor" />
        <path d="m8.5 17 2.6-2.7 1.8 1.8 1.5-1.6 1.9 2.5" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
  },
  {
    title: 'Built-in & custom skills',
    body: 'Start with focused skills for flowcharts, UML, ER and sequence diagrams, or create your own skill for a specific domain, workflow or drawing style.',
    icon: (
      <svg viewBox="0 0 24 24" width={22} height={22} fill="none">
        <rect x={4} y={5} width={6} height={5} rx={1.2} stroke="currentColor" strokeWidth={2} />
        <rect x={14} y={14} width={6} height={5} rx={1.2} stroke="currentColor" strokeWidth={2} />
        <path d="M10 7.5h4.5v4.5M7 10v3.5h7" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
        <path d="M17 4l.55 1.55L19 6.1l-1.45.55L17 8.2l-.55-1.55L15 6.1l1.45-.55z" fill="currentColor" />
      </svg>
    ),
  },
];

// The supplied app icon is reused anywhere the compact brand mark is needed.
function BrandAppIcon({ size = 32, className = '' }: { size?: number; className?: string }) {
  return (
    <Image
      src="/brand/freedraw-app-icon-v2.png"
      alt=""
      width={size}
      height={size}
      className={className}
      aria-hidden="true"
    />
  );
}

function CanvasArt() {
  // Keep the homepage sample centered and readable inside the preview canvas.
  const processNode = (x: number, y: number, w: number, h: number, c: string, label: string) => (
    <g key={`nd${x}${y}`}>
      <rect x={x} y={y} width={w} height={h} rx={8} fill={c} stroke="rgba(0,0,0,.14)" strokeWidth={1.5} />
      <text x={x + w / 2} y={y + h / 2 + 4} textAnchor="middle" fontFamily="var(--font-sans)" fontSize={13} fontWeight={600} fill="#3a3a40">
        {label}
      </text>
    </g>
  );
  const terminalNode = (x: number, y: number, w: number, h: number, label: string) => (
    <g key={`tm${x}${y}`}>
      <rect x={x} y={y} width={w} height={h} rx={h / 2} fill={PAL.green} stroke="rgba(78,118,60,.26)" strokeWidth={1.6} />
      <text x={x + w / 2} y={y + h / 2 + 4} textAnchor="middle" fontFamily="var(--font-sans)" fontSize={13} fontWeight={700} fill="#2e3a2e">
        {label}
      </text>
    </g>
  );
  const decisionNode = (cx: number, cy: number, w: number, h: number, label: string) => (
    <g key={`dc${cx}${cy}`}>
      <path d={`M${cx} ${cy - h / 2} L${cx + w / 2} ${cy} L${cx} ${cy + h / 2} L${cx - w / 2} ${cy} Z`} fill={PAL.amber} stroke="rgba(170,126,22,.35)" strokeWidth={1.5} />
      <text x={cx} y={cy + 4} textAnchor="middle" fontFamily="var(--font-sans)" fontSize={13} fontWeight={700} fill="#3a3a40">
        {label}
      </text>
    </g>
  );
  const edge = (x1: number, y1: number, x2: number, y2: number) => (
    <line key={`eg${x1}${y1}`} x1={x1} y1={y1} x2={x2} y2={y2} stroke="#a8b0be" strokeWidth={2} markerEnd="url(#fd-ar)" />
  );
  const elbowEdge = (points: string) => <polyline key={points} points={points} fill="none" stroke="#a8b0be" strokeWidth={2} markerEnd="url(#fd-ar)" />;
  return (
    <svg width={700} height={480} viewBox="0 0 700 480" style={{ display: 'block', maxWidth: '100%', height: 'auto' }}>
      <defs>
        <marker id="fd-ar" markerWidth={9} markerHeight={9} refX={7} refY={3.5} orient="auto">
          <path d="M0 0 L7 3.5 L0 7" fill="none" stroke="#a8b0be" strokeWidth={1.7} />
        </marker>
      </defs>
      <text x={350} y={28} textAnchor="middle" fontFamily={DISPLAY} fontSize={17} fontWeight={600} fill="#2a2a2e">
        User Login Flow
      </text>
      {terminalNode(150, 52, 170, 32, 'Start')}
      {processNode(155, 122, 160, 32, PAL.blue, 'Open App')}
      {decisionNode(235, 225, 270, 76, 'Logged In?')}
      {processNode(155, 310, 160, 32, PAL.blue, 'Browse Content')}
      {processNode(155, 377, 160, 32, PAL.blue, 'Perform Action')}
      {terminalNode(150, 444, 170, 32, 'End')}
      {processNode(470, 200, 170, 50, PAL.red, 'Sign Up / Log In')}
      {edge(235, 84, 235, 122)}
      {edge(235, 154, 235, 187)}
      {edge(235, 263, 235, 310)}
      {edge(235, 342, 235, 377)}
      {edge(235, 409, 235, 444)}
      {edge(370, 225, 470, 225)}
      {elbowEdge('555 250 555 326 315 326')}
      <text x={188} y={303} textAnchor="middle" fontFamily="var(--font-sans)" fontSize={12} fontWeight={700} fill="#585858">
        Yes
      </text>
      <text x={420} y={216} textAnchor="middle" fontFamily="var(--font-sans)" fontSize={12} fontWeight={700} fill="#585858">
        No
      </text>
      <text x={430} y={318} textAnchor="middle" fontFamily="var(--font-sans)" fontSize={12} fontWeight={700} fill="#585858">
        Login Successful
      </text>
    </svg>
  );
}

export default function Home() {
  const router = useRouter();
  const [isSignedIn, setIsSignedIn] = useState(false);
  const [introVariant, setIntroVariant] = useState<'full' | 'quick'>('full');
  const [showIntro, setShowIntro] = useState(true);
  const [landingInput, setLandingInput] = useState('');
  const [landingFocus, setLandingFocus] = useState(false);

  useEffect(() => {
    let hideTimer: number | undefined;
    // Defer client-only session and motion checks until after hydration.
    const frame = window.requestAnimationFrame(() => {
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      let hasSeenIntro = false;

      try {
        hasSeenIntro = window.sessionStorage.getItem(HOME_INTRO_SESSION_KEY) === '1';
        window.sessionStorage.setItem(HOME_INTRO_SESSION_KEY, '1');
      } catch {
        // A blocked session store should not prevent the homepage from opening.
      }

      const nextVariant = hasSeenIntro || reduceMotion ? 'quick' : 'full';
      setIntroVariant(nextVariant);
      hideTimer = window.setTimeout(
        () => setShowIntro(false),
        reduceMotion ? 0 : nextVariant === 'full' ? 1050 : 180,
      );
    });

    return () => {
      window.cancelAnimationFrame(frame);
      if (hideTimer !== undefined) window.clearTimeout(hideTimer);
    };
  }, []);

  // Keep the public homepage available while reflecting the existing session.
  useEffect(() => {
    let cancelled = false;
    const browserUserInfo = getUserInfo();
    agentApi
      .me()
      .then(({ data }) => {
        if (!cancelled) setIsSignedIn(Boolean(data?.status === 'SUCCESS' && data.userId));
      })
      .catch(() => {
        // Retain the local session hint if the account check is temporarily unavailable.
        if (!cancelled) setIsSignedIn(Boolean(browserUserInfo));
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  const goSignin = () => router.push('/login');
  const goSignup = () => router.push('/register');
  // Carry the typed prompt into a fresh editor session so the composer opens pre-filled.
  const goEditor = () => {
    const prompt = landingInput.trim();
    router.push(prompt ? `/drawio?new=1&prompt=${encodeURIComponent(prompt)}` : '/drawio?new=1');
  };

  const landingBorder = landingFocus ? ACCENT : 'rgba(0,0,0,.1)';

  return (
    <div className={`fd-intro-${introVariant}`} style={{ minHeight: '100vh', ['--accent' as string]: ACCENT }}>
      <style>{`
        @keyframes fdReveal{0%{opacity:0;transform:scale(.82)}100%{opacity:1;transform:scale(1)}}
        @keyframes fdDock{0%{transform:translate3d(0,0,0) scale(1);opacity:1}100%{transform:translate3d(var(--fd-dock-x),var(--fd-dock-y),0) scale(.18);opacity:0}}
        @keyframes fdVeil{0%{opacity:1;visibility:visible}100%{opacity:0;visibility:hidden}}
        @keyframes fdRiseLine{0%{transform:translateY(112%)}100%{transform:translateY(0)}}
        @keyframes fdRise{0%{opacity:0;transform:translateY(18px)}100%{opacity:1;transform:none}}
        @keyframes fdNavLogo{0%{opacity:0}100%{opacity:1}}
        .fd-scroll::-webkit-scrollbar{width:9px;height:9px}
        .fd-scroll::-webkit-scrollbar-thumb{background:rgba(0,0,0,.14);border-radius:9px;border:2px solid transparent;background-clip:padding-box}
        .fd-scroll::-webkit-scrollbar-track{background:transparent}
        .fd-veil{--fd-dock-x:calc(-50vw + max(90px,calc((100vw - 1440px)/2 + 90px)));--fd-dock-y:calc(-50vh + 32px);position:fixed;inset:0;z-index:60;background:#f5f5f4;display:flex;align-items:center;justify-content:center;pointer-events:none}
        .fd-veil-full{animation:fdVeil .22s ease .78s forwards}
        .fd-veil-quick{animation:fdVeil .16s ease forwards}
        .fd-scene{transform-origin:center;will-change:transform,opacity}
        .fd-scene-full{animation:fdReveal .25s ease-out both,fdDock .55s cubic-bezier(.55,0,.2,1) .28s forwards}
        .fd-scene-quick{display:none}
        .fd-h-line{display:block;overflow:hidden}
        .fd-h-line>span{display:inline-block;transform:translateY(112%)}
        .fd-in{opacity:0}
        .fd-intro-full .fd-title-primary{animation:fdRiseLine .52s cubic-bezier(.5,0,.15,1) .52s forwards}
        .fd-intro-full .fd-title-secondary{animation:fdRiseLine .52s cubic-bezier(.5,0,.15,1) .61s forwards}
        .fd-intro-full .fd-hero-copy{animation:fdRise .42s cubic-bezier(.4,0,.2,1) .7s forwards}
        .fd-intro-full .fd-prompt-shell{animation:fdRise .42s cubic-bezier(.4,0,.2,1) .78s forwards}
        .fd-intro-full .fd-nav-logo{opacity:0;animation:fdNavLogo .2s ease .72s forwards}
        .fd-intro-quick .fd-title-primary{animation:fdRiseLine .28s cubic-bezier(.5,0,.15,1) forwards}
        .fd-intro-quick .fd-title-secondary{animation:fdRiseLine .28s cubic-bezier(.5,0,.15,1) .04s forwards}
        .fd-intro-quick .fd-hero-copy{animation:fdRise .3s cubic-bezier(.4,0,.2,1) .08s forwards}
        .fd-intro-quick .fd-prompt-shell{animation:fdRise .3s cubic-bezier(.4,0,.2,1) .12s forwards}
        .fd-intro-quick .fd-nav-logo{animation:fdNavLogo .15s ease forwards}
        .fd-nav-signin:hover{background:rgba(0,0,0,.05)}
        .fd-btn-accent:hover{filter:brightness(1.12)}
        .fd-chip:hover{border-color:#bdbbb4;color:#17171a}
        .fd-cta-ghost:hover{background:#faf9f7}
        @media (max-width: 767px) {
          .fd-veil{--fd-dock-x:calc(-50vw + 76px);--fd-dock-y:calc(-50vh + 34px)}
          .fd-nav{padding:16px 18px!important;gap:12px!important}
          .fd-nav-actions{gap:6px!important}
          .fd-nav-signin,.fd-nav-actions .fd-btn-accent{height:36px!important;padding:0 12px!important;font-size:13px!important}
          .fd-hero{max-width:100%!important;padding:30px 18px 0!important}
          .fd-hero-title{font-size:40px!important;line-height:1.04!important;margin:28px 0 16px!important}
          .fd-hero-copy{font-size:15px!important;margin-bottom:24px!important}
          .fd-prompt-shell{max-width:100%!important}
          .fd-prompt-bar{align-items:stretch!important;flex-direction:column!important;gap:8px!important;padding:12px!important;border-radius:14px!important}
          .fd-prompt-input{width:100%!important;height:42px!important;font-size:15px!important}
          .fd-draw-btn{width:100%!important;height:42px!important;justify-content:center!important}
          .fd-example-row{flex-wrap:wrap!important;justify-content:center!important;overflow-x:visible!important;padding-bottom:0!important}
          .fd-chip{flex:0 0 auto!important}
          .fd-preview-section{margin-top:36px!important;padding:0 14px!important}
          .fd-browser-preview{border-radius:14px 14px 0 0!important}
          .fd-preview-body{height:auto!important;flex-direction:column!important}
          .fd-canvas-pane{min-height:260px!important;padding:10px!important}
          .fd-canvas-pane svg{width:100%!important;min-width:320px}
          .fd-agent-preview{width:100%!important;min-height:170px!important;border-left:0!important;border-top:1px solid #ededed!important}
          .fd-types-section,.fd-features-section,.fd-cta-section{margin-top:42px!important;padding:0 18px!important}
          .fd-type-grid{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:8px!important}
          .fd-type-chip{height:36px!important;justify-content:center!important;padding:0 10px!important;font-size:12.5px!important}
          .fd-feature-grid{grid-template-columns:1fr!important;gap:12px!important}
          .fd-feature-card{padding:18px!important}
          .fd-cta-card{padding:32px 20px!important;border-radius:18px!important}
          .fd-cta-title{font-size:26px!important}
          .fd-cta-actions{flex-direction:column!important}
          .fd-cta-actions button{width:100%!important}
        }
        @media (min-width: 768px) and (max-width: 1023px) {
          .fd-feature-grid{grid-template-columns:repeat(2,minmax(0,1fr))!important}
          .fd-agent-preview{width:240px!important}
          .fd-preview-body{height:440px!important}
        }
        @media (prefers-reduced-motion: reduce) {
          .fd-veil{display:none}
          .fd-h-line>span,.fd-in,.fd-nav-logo{animation:none!important;opacity:1!important;transform:none!important}
        }
      `}</style>

      <div
        className="fd-scroll"
        style={{
          minHeight: '100vh',
          background:
            'radial-gradient(900px 460px at 50% -8%,rgba(0,0,0,.045),transparent 60%),#f5f5f4',
        }}
      >
        {/* The full intro runs once per tab; return visits get only a short fade. */}
        {showIntro && (
          <div className={`fd-veil fd-veil-${introVariant}`} aria-hidden="true">
            <BrandAppIcon size={172} className={`fd-scene fd-scene-${introVariant}`} />
          </div>
        )}

        {/* nav */}
        <div className="fd-nav" style={{ display: 'flex', alignItems: 'center', gap: 20, maxWidth: PAGE_MAX_WIDTH, margin: '0 auto', padding: '22px 32px' }}>
          <Link className="fd-nav-logo" href="/" aria-label="FreeDraw home" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Image src="/brand/freedraw-wordmark-on-light-v2.png" alt="FreeDraw" width={116} height={20} priority />
          </Link>
          <div style={{ flex: 1 }} />
          <div className="fd-nav-actions" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {isSignedIn ? (
              <AdminAccountMenu logoutHref="/" />
            ) : (
              <>
                <button
                  className="fd-nav-signin"
                  onClick={goSignin}
                  style={{ height: 38, padding: '0 16px', background: 'transparent', border: 'none', borderRadius: 10, fontSize: 13.5, fontWeight: 600, color: '#4a4a4a', cursor: 'pointer' }}
                >
                  Sign in
                </button>
                <button
                  className="fd-btn-accent"
                  onClick={goSignup}
                  style={{ height: 38, padding: '0 17px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: 10, fontSize: 13.5, fontWeight: 600, cursor: 'pointer', boxShadow: '0 2px 6px rgba(50,48,45,.15)' }}
                >
                  Sign up
                </button>
              </>
            )}
          </div>
        </div>

        {/* hero */}
        <div className="fd-hero" style={{ maxWidth: 800, margin: '0 auto', padding: '56px 32px 0', textAlign: 'center' }}>
          <h1 className="fd-hero-title" style={{ fontFamily: DISPLAY, fontWeight: 600, fontSize: 56, lineHeight: 1.05, letterSpacing: '-.03em', color: '#17171a', margin: '48px 0 20px' }}>
            <span className="fd-h-line">
              <span className="fd-title-primary">Describe it.</span>
            </span>
            <span className="fd-h-line" style={{ position: 'relative', display: 'inline-block' }}>
              <span className="fd-title-secondary">Watch it draw.</span>
            </span>
          </h1>
          <p className="fd-in fd-hero-copy" style={{ fontSize: 17, lineHeight: 1.6, color: '#6f6c64', maxWidth: 500, margin: '0 auto 34px' }}>
            Describe any diagram in plain words and watch it take shape — inside the full draw.io editor, with an AI copilot that edits right alongside you.
          </p>

          {/* prompt bar (main visual) */}
          <div className="fd-in fd-prompt-shell" style={{ maxWidth: 600, margin: '0 auto' }}>
            <div className="fd-prompt-bar" style={{ display: 'flex', alignItems: 'center', gap: 10, background: '#fff', border: `1.5px solid ${landingBorder}`, borderRadius: 16, padding: '9px 9px 9px 18px', boxShadow: '0 10px 34px rgba(40,38,36,.09)', transition: 'border-color .15s' }}>
              <svg width={19} height={19} viewBox="0 0 24 24" fill="none" style={{ flex: 'none', color: '#9a968c' }}>
                <path d="M12 3l2.1 6.3L20.5 11l-6.4 1.7L12 19l-2.1-6.3L3.5 11l6.4-1.7z" stroke="currentColor" strokeWidth={1.6} strokeLinejoin="round" />
              </svg>
              <input
                className="fd-prompt-input"
                value={landingInput}
                onChange={e => setLandingInput(e.target.value)}
                onFocus={() => setLandingFocus(true)}
                onBlur={() => setLandingFocus(false)}
                onKeyDown={e => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    goEditor();
                  }
                }}
                placeholder="Please help me draw a user login flowchart…"
                style={{ flex: 1, border: 'none', background: 'transparent', fontSize: 15.5, color: '#1a1a1a', height: 38 }}
              />
              <button
                className="fd-btn-accent fd-draw-btn"
                onClick={goEditor}
                style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 7, height: 44, padding: '0 20px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: 11, fontSize: 14.5, fontWeight: 600, cursor: 'pointer' }}
              >
                Draw
                <svg width={16} height={16} viewBox="0 0 24 24" fill="none">
                  <path d="M5 12h13M13 6l6 6-6 6" stroke="#fff" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
            </div>
            <div className="fd-example-row" style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'center', gap: 8, marginTop: 16 }}>
              <span style={{ fontSize: 12.5, color: '#a5a29a', alignSelf: 'center' }}>Try:</span>
              {HERO_EXAMPLES.map(ex => (
                <button
                  key={ex.label}
                  className="fd-chip"
                  onClick={() => setLandingInput(ex.prompt)}
                  style={{ height: 30, padding: '0 13px', background: '#fff', border: '1px solid rgba(0,0,0,.09)', borderRadius: 20, fontSize: 12.5, fontWeight: 500, color: '#4f4f55', cursor: 'pointer' }}
                >
                  {ex.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* product preview */}
        <div className="fd-preview-section" style={{ maxWidth: PAGE_MAX_WIDTH, margin: '56px auto 0', padding: '0 32px' }}>
          <div className="fd-browser-preview" style={{ borderRadius: '16px 16px 0 0', border: '1px solid rgba(0,0,0,.1)', borderBottom: 'none', background: '#fff', overflow: 'hidden', boxShadow: '0 -1px 0 rgba(255,255,255,.6),0 24px 60px rgba(40,38,36,.12)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, height: 38, padding: '0 15px', background: '#fafafa', borderBottom: '1px solid #ececec' }}>
              <span style={{ width: 11, height: 11, borderRadius: '50%', background: '#e0ded9' }} />
              <span style={{ width: 11, height: 11, borderRadius: '50%', background: '#e0ded9' }} />
              <span style={{ width: 11, height: 11, borderRadius: '50%', background: '#e0ded9' }} />
              <span style={{ marginLeft: 12, fontSize: 12, color: '#a5a29a', fontFamily: MONO }}>FreeDraw — user-login-flow.drawio</span>
            </div>
            <div className="fd-preview-body" style={{ display: 'flex', height: 500 }}>
              <div className="fd-canvas-pane" style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'relative', background: '#fff', backgroundImage: 'linear-gradient(rgba(0,0,0,.045) 1px,transparent 1px),linear-gradient(90deg,rgba(0,0,0,.045) 1px,transparent 1px)', backgroundSize: '15px 15px', overflow: 'hidden' }}>
                <CanvasArt />
              </div>
              <div className="fd-agent-preview" style={{ width: 270, flex: 'none', borderLeft: '1px solid #ededed', display: 'flex', flexDirection: 'column', background: '#fff' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '13px 14px', borderBottom: '1px solid #f0f0f0' }}>
                  <div style={{ width: 28, height: 28, borderRadius: 8, background: 'linear-gradient(145deg,#2a2933,#151419)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    <svg width={14} height={14} viewBox="0 0 24 24" fill="#fff">
                      <path d="M12 2.5l1.9 5.6 5.6 1.9-5.6 1.9L12 17.5l-1.9-5.6-5.6-1.9 5.6-1.9L12 2.5z" />
                    </svg>
                  </div>
                  <div style={{ fontFamily: DISPLAY, fontWeight: 600, fontSize: 14 }}>Agent</div>
                </div>
                <div style={{ flex: 1, padding: 14, display: 'flex', flexDirection: 'column', gap: 11 }}>
                  <div style={{ alignSelf: 'flex-end', maxWidth: 240, background: 'var(--accent)', color: '#fff', borderRadius: '12px 12px 3px 12px', padding: '8px 11px', fontSize: 12.5, lineHeight: 1.45 }}>
                    Please help me draw a user login flowchart
                  </div>
                  <div style={{ alignSelf: 'flex-start', maxWidth: 245, background: '#faf9f7', border: '1px solid rgba(0,0,0,.06)', borderRadius: '3px 12px 12px 12px', padding: '8px 11px', fontSize: 12.5, lineHeight: 1.45, color: '#33333a' }}>
                    Diagram completed (7 nodes · 7 edges). Want me to adjust the layout?
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* diagram types */}
        <div className="fd-types-section" style={{ maxWidth: PAGE_MAX_WIDTH, margin: '60px auto 0', padding: '0 32px', textAlign: 'center' }}>
          <div style={{ fontFamily: MONO, fontSize: 11.5, letterSpacing: '.08em', color: '#a5a29a', textTransform: 'uppercase', marginBottom: 20 }}>
            Built-in skill coverage
          </div>
          <div className="fd-type-grid" style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'center', gap: 10 }}>
            {DIAGRAM_TYPES.map(d => (
              <div key={d} className="fd-type-chip" style={{ height: 38, padding: '0 18px', display: 'flex', alignItems: 'center', background: '#fff', border: '1px solid rgba(0,0,0,.08)', borderRadius: 10, fontSize: 13.5, fontWeight: 500, color: '#3f3f45' }}>
                {d}
              </div>
            ))}
          </div>
          <div style={{ fontSize: 13, color: '#a5a29a', marginTop: 16 }}>
            Our built-in skills currently cover these diagram types, and we&apos;re exploring more.
          </div>
        </div>

        {/* features */}
        <div className="fd-features-section" style={{ maxWidth: PAGE_MAX_WIDTH, margin: '64px auto 0', padding: '0 32px' }}>
          <div className="fd-feature-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 18 }}>
            {FEATURES.map(f => (
              <div key={f.title} className="fd-feature-card" style={{ background: '#fff', border: '1px solid rgba(0,0,0,.08)', borderRadius: 16, padding: '24px 22px' }}>
                <div style={{ width: 42, height: 42, borderRadius: 11, background: '#f4f3f1', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#34333b', marginBottom: 16 }}>
                  {f.icon}
                </div>
                <div style={{ fontFamily: DISPLAY, fontWeight: 600, fontSize: 16.5, color: '#1c1c20', marginBottom: 7 }}>{f.title}</div>
                <div style={{ fontSize: 13.5, lineHeight: 1.6, color: '#7a776f' }}>{f.body}</div>
              </div>
            ))}
          </div>
        </div>

        {/* closing CTA */}
        <div className="fd-cta-section" style={{ maxWidth: PAGE_MAX_WIDTH, margin: '64px auto 0', padding: '0 32px 80px' }}>
          <div className="fd-cta-card" style={{ position: 'relative', overflow: 'hidden', background: '#fff', border: '1px solid rgba(0,0,0,.09)', borderRadius: 22, padding: '52px 40px', textAlign: 'center', boxShadow: '0 12px 40px rgba(40,38,36,.07)' }}>
            <div style={{ position: 'absolute', inset: 0, backgroundImage: 'linear-gradient(rgba(0,0,0,.028) 1px,transparent 1px),linear-gradient(90deg,rgba(0,0,0,.028) 1px,transparent 1px)', backgroundSize: '22px 22px', WebkitMaskImage: 'radial-gradient(circle at 50% 40%,#000,transparent 72%)', maskImage: 'radial-gradient(circle at 50% 40%,#000,transparent 72%)' }} />
            <div style={{ position: 'relative', width: 52, height: 52, margin: '0 auto 20px', borderRadius: 14, overflow: 'hidden', boxShadow: '0 4px 14px rgba(50,48,45,.22)' }}>
              <BrandAppIcon size={52} />
            </div>
            <h2 className="fd-cta-title" style={{ position: 'relative', fontFamily: DISPLAY, fontWeight: 600, fontSize: 32, letterSpacing: '-.02em', color: '#17171a', margin: '0 0 12px' }}>
              Start your first diagram
              <br />
              from a single sentence
            </h2>
            <p style={{ position: 'relative', fontSize: 15, color: '#7a776f', margin: '0 0 28px' }}>
              Free tokens to start — or bring your own LLM API provider.
            </p>
            <div className="fd-cta-actions" style={{ position: 'relative', display: 'flex', justifyContent: 'center', gap: 10 }}>
              <button
                className="fd-btn-accent"
                onClick={goSignup}
                style={{ height: 48, padding: '0 26px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: 12, fontSize: 15, fontWeight: 600, cursor: 'pointer', boxShadow: '0 3px 10px rgba(50,48,45,.18)' }}
              >
                Create free account
              </button>
              <button
                className="fd-cta-ghost"
                onClick={goEditor}
                style={{ height: 48, padding: '0 22px', background: '#fff', color: '#3a3a3a', border: '1px solid rgba(0,0,0,.12)', borderRadius: 12, fontSize: 15, fontWeight: 600, cursor: 'pointer' }}
              >
                Try the editor
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
